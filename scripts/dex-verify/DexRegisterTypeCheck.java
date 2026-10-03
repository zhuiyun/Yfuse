import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.AnalysisException;
import org.jf.dexlib2.analysis.AnalyzedInstruction;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.ClassProvider;
import org.jf.dexlib2.analysis.DexClassProvider;
import org.jf.dexlib2.analysis.MethodAnalyzer;
import org.jf.dexlib2.analysis.RegisterType;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.TypeReference;

/**
 * Flags DEX code that ART's verifier rejects because a register holds the wrong kind of value: an
 * object where an int, float or long is needed, a primitive where an object is needed, or a
 * register that no path has written. ART rejects the whole class for one such instruction and
 * throws VerifyError the first time the class is used, which a release build cannot show until
 * that screen opens on a device.
 *
 * Register types come from dexlib2's verifier model (the one baksmali's register info prints),
 * with ART's instance-of narrowing. Only kind mismatches are reported, never int/float or
 * boolean/int subtleties, so a finding is an instruction no verifier accepts.
 *
 * Usage: java -cp <dexlib2 classpath> DexRegisterTypeCheck.java <apk-or-dex>...
 * Exit status: 0 clean, 1 findings or methods that could not be analysed, 2 bad input.
 */
public final class DexRegisterTypeCheck {
    private enum Need { NARROW, WIDE, REFERENCE, NARROW_OR_REFERENCE }

    private static final int MIN_API = 26;
    private static final int MAX_FINDINGS_PRINTED = 50;
    /** D8, R8 and L8 leave a marker string such as ~~R8{..."version":"9.1.31"} in their output. */
    private static final Pattern COMPILER_MARKER = Pattern.compile("^~~([DLR]8)\\{.*\"version\":\"([^\"]+)\"");

    private int classes;
    private int methods;
    private final Set<String> compilers = new TreeSet<>();
    private final List<String> findings = new ArrayList<>();
    private final List<String> analysisFailures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: DexRegisterTypeCheck <apk-or-dex>...");
            System.exit(2);
        }
        boolean failed = false;
        for (String path : args) {
            File file = new File(path);
            if (!file.isFile()) {
                System.err.println("Not a file: " + path);
                System.exit(2);
            }
            DexRegisterTypeCheck check = new DexRegisterTypeCheck();
            check.run(file);
            failed |= check.report(path);
        }
        System.exit(failed ? 1 : 0);
    }

    private void run(File file) throws Exception {
        MultiDexContainer<? extends DexBackedDexFile> container =
            DexFileFactory.loadDexContainer(file, Opcodes.forApi(MIN_API));
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        List<ClassProvider> providers = new ArrayList<>();
        for (String entry : container.getDexEntryNames()) {
            DexBackedDexFile dex = container.getEntry(entry).getDexFile();
            dexFiles.add(dex);
            providers.add(new DexClassProvider(dex));
        }
        // Every dex of the APK is on the class path so cross-dex hierarchies merge as ART merges
        // them; platform classes stay unresolved, which only ever widens a reference type.
        ClassPath classPath = new ClassPath(providers, false, ClassPath.NOT_SPECIFIED);
        for (DexBackedDexFile dex : dexFiles) {
            for (String string : dex.getStringSection()) {
                Matcher marker = COMPILER_MARKER.matcher(string);
                if (marker.find()) compilers.add(marker.group(1) + " " + marker.group(2));
            }
            for (ClassDef classDef : dex.getClasses()) {
                classes++;
                for (Method method : classDef.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    methods++;
                    checkMethod(classPath, method);
                }
            }
        }
    }

    private boolean report(String path) {
        System.out.printf(
            "%s (%s): %d classes, %d methods with code, %d findings, %d methods not analysable%n",
            path, compilers.isEmpty() ? "compiler unknown" : String.join(", ", compilers),
            classes, methods, findings.size(), analysisFailures.size());
        for (int i = 0; i < findings.size() && i < MAX_FINDINGS_PRINTED; i++) {
            System.out.println(findings.get(i));
        }
        if (findings.size() > MAX_FINDINGS_PRINTED) {
            System.out.printf("... %d more findings%n", findings.size() - MAX_FINDINGS_PRINTED);
        }
        for (int i = 0; i < analysisFailures.size() && i < MAX_FINDINGS_PRINTED; i++) {
            System.out.println(analysisFailures.get(i));
        }
        return !findings.isEmpty() || !analysisFailures.isEmpty();
    }

    private void checkMethod(ClassPath classPath, Method method) {
        String name = describe(method);
        MethodAnalyzer analyzer;
        try {
            analyzer = new MethodAnalyzer(classPath, method, null, false);
        } catch (RuntimeException e) {
            analysisFailures.add("NOT ANALYSED " + name + ": " + e);
            return;
        }
        AnalysisException failure = analyzer.getAnalysisException();
        if (failure != null) {
            analysisFailures.add("NOT ANALYSED " + name + ": " + failure.getMessage());
            return;
        }
        for (AnalyzedInstruction analyzed : analyzer.getAnalyzedInstructions()) {
            // Code no path reaches is never verified by type, by ART or here.
            if (analyzed.getPredecessorCount() == 0 && !analyzed.isBeginningInstruction()) continue;
            Instruction instruction = analyzed.getInstruction();
            List<int[]> operands = operands(instruction, method);
            for (int[] operand : operands) {
                String problem = problem(analyzed, operand[0], Need.values()[operand[1]]);
                if (problem != null) {
                    findings.add(String.format(
                        "FAIL %s%n  [0x%X] %s: %s",
                        name,
                        analyzer.getInstructionAddress(analyzed),
                        instruction.getOpcode().name,
                        problem));
                    return; // ART stops at the first hard failure of a method as well.
                }
            }
        }
    }

    private static String problem(AnalyzedInstruction analyzed, int register, Need need) {
        RegisterType type = analyzed.getPreInstructionRegisterType(register);
        byte c = type.category;
        boolean ok;
        switch (need) {
            case NARROW:
                ok = isNarrowPrimitive(c);
                break;
            case REFERENCE:
                ok = isReference(c);
                break;
            case NARROW_OR_REFERENCE:
                ok = isNarrowPrimitive(c) || isReference(c);
                break;
            case WIDE:
                RegisterType high = analyzed.getPreInstructionRegisterType(register + 1);
                ok = (c == RegisterType.LONG_LO || c == RegisterType.DOUBLE_LO) &&
                    (high.category == RegisterType.LONG_HI || high.category == RegisterType.DOUBLE_HI);
                if (!ok) {
                    return String.format("register pair v%d/v%d has types %s/%s but expected a long or double",
                        register, register + 1, type, high);
                }
                return null;
            default:
                throw new IllegalStateException(need.name());
        }
        if (ok) return null;
        String expected = need == Need.NARROW ? "an int, boolean or float"
            : need == Need.REFERENCE ? "an object reference" : "an int or an object reference";
        return String.format("register v%d has type %s but expected %s", register, type, expected);
    }

    private static boolean isNarrowPrimitive(byte c) {
        return c >= RegisterType.NULL && c <= RegisterType.FLOAT;
    }

    private static boolean isReference(byte c) {
        return c == RegisterType.NULL || c == RegisterType.REFERENCE ||
            c == RegisterType.UNINIT_REF || c == RegisterType.UNINIT_THIS;
    }

    /** The registers an instruction reads, each with the kind of value it must hold. */
    private static List<int[]> operands(Instruction instruction, Method method) {
        List<int[]> out = new ArrayList<>();
        Opcode opcode = instruction.getOpcode();
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
                add(out, b(instruction), Need.NARROW);
                break;
            case MOVE_WIDE: case MOVE_WIDE_FROM16: case MOVE_WIDE_16:
                add(out, b(instruction), Need.WIDE);
                break;
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                add(out, b(instruction), Need.REFERENCE);
                break;
            case RETURN: case RETURN_WIDE: case RETURN_OBJECT:
                add(out, a(instruction), needFor(method.getReturnType()));
                break;
            case MONITOR_ENTER: case MONITOR_EXIT:
            case CHECK_CAST:
            case THROW:
            case FILL_ARRAY_DATA:
                add(out, a(instruction), Need.REFERENCE);
                break;
            case INSTANCE_OF:
            case ARRAY_LENGTH:
                add(out, b(instruction), Need.REFERENCE);
                break;
            case NEW_ARRAY:
                add(out, b(instruction), Need.NARROW);
                break;
            case FILLED_NEW_ARRAY: case FILLED_NEW_ARRAY_RANGE: {
                String arrayType = ((TypeReference) ((ReferenceInstruction) instruction).getReference()).getType();
                Need element = isPrimitive(arrayType.substring(1)) ? Need.NARROW : Need.REFERENCE;
                for (int register : argumentRegisters(instruction)) add(out, register, element);
                break;
            }
            case PACKED_SWITCH: case SPARSE_SWITCH:
            case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ:
                add(out, a(instruction), Need.NARROW);
                break;
            case IF_EQZ: case IF_NEZ:
                add(out, a(instruction), Need.NARROW_OR_REFERENCE);
                break;
            case IF_EQ: case IF_NE:
                add(out, a(instruction), Need.NARROW_OR_REFERENCE);
                add(out, b(instruction), Need.NARROW_OR_REFERENCE);
                break;
            case IF_LT: case IF_GE: case IF_GT: case IF_LE:
                add(out, a(instruction), Need.NARROW);
                add(out, b(instruction), Need.NARROW);
                break;
            case CMPL_FLOAT: case CMPG_FLOAT:
                add(out, b(instruction), Need.NARROW);
                add(out, c(instruction), Need.NARROW);
                break;
            case CMPL_DOUBLE: case CMPG_DOUBLE: case CMP_LONG:
                add(out, b(instruction), Need.WIDE);
                add(out, c(instruction), Need.WIDE);
                break;
            case AGET: case AGET_WIDE: case AGET_OBJECT: case AGET_BOOLEAN:
            case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
                add(out, b(instruction), Need.REFERENCE);
                add(out, c(instruction), Need.NARROW);
                break;
            case APUT: case APUT_BOOLEAN: case APUT_BYTE: case APUT_CHAR: case APUT_SHORT:
                add(out, a(instruction), Need.NARROW);
                add(out, b(instruction), Need.REFERENCE);
                add(out, c(instruction), Need.NARROW);
                break;
            case APUT_WIDE:
                add(out, a(instruction), Need.WIDE);
                add(out, b(instruction), Need.REFERENCE);
                add(out, c(instruction), Need.NARROW);
                break;
            case APUT_OBJECT:
                add(out, a(instruction), Need.REFERENCE);
                add(out, b(instruction), Need.REFERENCE);
                add(out, c(instruction), Need.NARROW);
                break;
            case IGET: case IGET_WIDE: case IGET_OBJECT: case IGET_BOOLEAN:
            case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
                add(out, b(instruction), Need.REFERENCE);
                break;
            case IPUT: case IPUT_WIDE: case IPUT_OBJECT: case IPUT_BOOLEAN:
            case IPUT_BYTE: case IPUT_CHAR: case IPUT_SHORT:
                add(out, a(instruction), needFor(fieldType(instruction)));
                add(out, b(instruction), Need.REFERENCE);
                break;
            case SPUT: case SPUT_WIDE: case SPUT_OBJECT: case SPUT_BOOLEAN:
            case SPUT_BYTE: case SPUT_CHAR: case SPUT_SHORT:
                add(out, a(instruction), needFor(fieldType(instruction)));
                break;
            case INVOKE_VIRTUAL: case INVOKE_SUPER: case INVOKE_DIRECT: case INVOKE_STATIC:
            case INVOKE_INTERFACE: case INVOKE_VIRTUAL_RANGE: case INVOKE_SUPER_RANGE:
            case INVOKE_DIRECT_RANGE: case INVOKE_STATIC_RANGE: case INVOKE_INTERFACE_RANGE: {
                MethodReference target = (MethodReference) ((ReferenceInstruction) instruction).getReference();
                int[] registers = argumentRegisters(instruction);
                int index = 0;
                boolean staticCall = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE;
                if (!staticCall && index < registers.length) add(out, registers[index++], Need.REFERENCE);
                for (CharSequence parameter : target.getParameterTypes()) {
                    if (index >= registers.length) break;
                    Need need = needFor(parameter.toString());
                    add(out, registers[index], need);
                    index += need == Need.WIDE ? 2 : 1;
                }
                break;
            }
            case NEG_INT: case NOT_INT: case NEG_FLOAT:
            case INT_TO_LONG: case INT_TO_FLOAT: case INT_TO_DOUBLE:
            case FLOAT_TO_INT: case FLOAT_TO_LONG: case FLOAT_TO_DOUBLE:
            case INT_TO_BYTE: case INT_TO_CHAR: case INT_TO_SHORT:
                add(out, b(instruction), Need.NARROW);
                break;
            case NEG_LONG: case NOT_LONG: case NEG_DOUBLE:
            case LONG_TO_INT: case LONG_TO_FLOAT: case LONG_TO_DOUBLE:
            case DOUBLE_TO_INT: case DOUBLE_TO_LONG: case DOUBLE_TO_FLOAT:
                add(out, b(instruction), Need.WIDE);
                break;
            case ADD_INT: case SUB_INT: case MUL_INT: case DIV_INT: case REM_INT:
            case AND_INT: case OR_INT: case XOR_INT: case SHL_INT: case SHR_INT: case USHR_INT:
            case ADD_FLOAT: case SUB_FLOAT: case MUL_FLOAT: case DIV_FLOAT: case REM_FLOAT:
                add(out, b(instruction), Need.NARROW);
                add(out, c(instruction), Need.NARROW);
                break;
            case ADD_LONG: case SUB_LONG: case MUL_LONG: case DIV_LONG: case REM_LONG:
            case AND_LONG: case OR_LONG: case XOR_LONG:
            case ADD_DOUBLE: case SUB_DOUBLE: case MUL_DOUBLE: case DIV_DOUBLE: case REM_DOUBLE:
                add(out, b(instruction), Need.WIDE);
                add(out, c(instruction), Need.WIDE);
                break;
            case SHL_LONG: case SHR_LONG: case USHR_LONG:
                add(out, b(instruction), Need.WIDE);
                add(out, c(instruction), Need.NARROW);
                break;
            case ADD_INT_2ADDR: case SUB_INT_2ADDR: case MUL_INT_2ADDR: case DIV_INT_2ADDR:
            case REM_INT_2ADDR: case AND_INT_2ADDR: case OR_INT_2ADDR: case XOR_INT_2ADDR:
            case SHL_INT_2ADDR: case SHR_INT_2ADDR: case USHR_INT_2ADDR:
            case ADD_FLOAT_2ADDR: case SUB_FLOAT_2ADDR: case MUL_FLOAT_2ADDR:
            case DIV_FLOAT_2ADDR: case REM_FLOAT_2ADDR:
                add(out, a(instruction), Need.NARROW);
                add(out, b(instruction), Need.NARROW);
                break;
            case ADD_LONG_2ADDR: case SUB_LONG_2ADDR: case MUL_LONG_2ADDR: case DIV_LONG_2ADDR:
            case REM_LONG_2ADDR: case AND_LONG_2ADDR: case OR_LONG_2ADDR: case XOR_LONG_2ADDR:
            case ADD_DOUBLE_2ADDR: case SUB_DOUBLE_2ADDR: case MUL_DOUBLE_2ADDR:
            case DIV_DOUBLE_2ADDR: case REM_DOUBLE_2ADDR:
                add(out, a(instruction), Need.WIDE);
                add(out, b(instruction), Need.WIDE);
                break;
            case SHL_LONG_2ADDR: case SHR_LONG_2ADDR: case USHR_LONG_2ADDR:
                add(out, a(instruction), Need.WIDE);
                add(out, b(instruction), Need.NARROW);
                break;
            case ADD_INT_LIT16: case RSUB_INT: case MUL_INT_LIT16: case DIV_INT_LIT16:
            case REM_INT_LIT16: case AND_INT_LIT16: case OR_INT_LIT16: case XOR_INT_LIT16:
            case ADD_INT_LIT8: case RSUB_INT_LIT8: case MUL_INT_LIT8: case DIV_INT_LIT8:
            case REM_INT_LIT8: case AND_INT_LIT8: case OR_INT_LIT8: case XOR_INT_LIT8:
            case SHL_INT_LIT8: case SHR_INT_LIT8: case USHR_INT_LIT8:
                add(out, b(instruction), Need.NARROW);
                break;
            default:
                // Constants, gotos, move-result/exception, new-instance, static gets, returns
                // without a value and the rare invoke-polymorphic/custom read nothing checked here.
                break;
        }
        return out;
    }

    private static void add(List<int[]> out, int register, Need need) {
        out.add(new int[] {register, need.ordinal()});
    }

    private static Need needFor(String type) {
        switch (type.charAt(0)) {
            case 'J': case 'D': return Need.WIDE;
            case 'L': case '[': return Need.REFERENCE;
            default: return Need.NARROW;
        }
    }

    private static boolean isPrimitive(String type) {
        return type.length() == 1 && "ZBSCIJFD".indexOf(type.charAt(0)) >= 0;
    }

    private static String fieldType(Instruction instruction) {
        return ((FieldReference) ((ReferenceInstruction) instruction).getReference()).getType();
    }

    private static int a(Instruction instruction) {
        return ((OneRegisterInstruction) instruction).getRegisterA();
    }

    private static int b(Instruction instruction) {
        return ((TwoRegisterInstruction) instruction).getRegisterB();
    }

    private static int c(Instruction instruction) {
        return ((ThreeRegisterInstruction) instruction).getRegisterC();
    }

    private static int[] argumentRegisters(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction range = (RegisterRangeInstruction) instruction;
            int[] registers = new int[range.getRegisterCount()];
            for (int i = 0; i < registers.length; i++) registers[i] = range.getStartRegister() + i;
            return registers;
        }
        FiveRegisterInstruction five = (FiveRegisterInstruction) instruction;
        int[] all = {five.getRegisterC(), five.getRegisterD(), five.getRegisterE(),
            five.getRegisterF(), five.getRegisterG()};
        int[] registers = new int[five.getRegisterCount()];
        System.arraycopy(all, 0, registers, 0, registers.length);
        return registers;
    }

    private static String describe(Method method) {
        StringBuilder out = new StringBuilder(method.getDefiningClass()).append("->").append(method.getName()).append('(');
        for (CharSequence parameter : method.getParameterTypes()) out.append(parameter);
        return out.append(')').append(method.getReturnType()).toString();
    }
}
