import java.io.File;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.DexFileFactory;
public class CheckOriginal {
 public static void main(String[] args) throws Exception {
  var dex=DexFileFactory.loadDexFile(new File(args[0]),Opcodes.getDefault());
  for (var c:dex.getClasses()) if(java.util.Set.of("Lhi3;","Lgi3;","Lvw4;","Lew4;").contains(c.getType())) {
   System.out.println(c.getType());
   for(var m:c.getMethods()) if(m.getName().equals("<init>")||c.getType().equals("Lew4;")&&m.getName().equals("c"))
    System.out.println(m.getName()+m.getParameterTypes()+"->"+m.getReturnType());
  }
 }
}