package qa.yfuse.gesture;

/** Verifies the same observation policy used by the Android touch recorder. */
public final class FrameProgressTest {
    private static void check(boolean value) {
        if(!value)throw new AssertionError("Frame progress policy failed");
    }
    public static void main(String[] ignored) {
        double[] anchor={-1,0,0};
        check(!Driver.verifiedProgress(anchor,4.625,69,66));
        check(!Driver.verifiedProgress(anchor,66.125,644,66));
        check(Driver.verifiedProgress(anchor,66.875,1135,66));
        anchor=new double[]{-1,0,0};
        check(!Driver.verifiedProgress(anchor,64,300,66));
        check(!Driver.verifiedProgress(anchor,66.125,644,66));
        check(anchor[2]==1);
        check(Driver.verifiedProgress(anchor,66.875,1135,66));
        anchor=new double[]{-1,0,0};
        check(!Driver.verifiedProgress(anchor,66,100,66));
        check(!Driver.verifiedProgress(anchor,66,9400,66));
        check(!Driver.verifiedProgress(anchor,66.25,9450,66));
        check(!Driver.verifiedProgress(anchor,90,9500,66));
        anchor=new double[]{-1,0,0};
        check(!Driver.verifiedProgress(anchor,67,100,66));
        check(!Driver.verifiedProgress(anchor,66,200,66));
        check(Driver.verifiedProgress(anchor,66.5,800,66));
        System.out.println("Frame progress: observed landing, discontinuity, frozen frame and backward reset passed");
    }
}
