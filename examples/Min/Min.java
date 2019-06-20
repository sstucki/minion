public class Min {
    //@ requires (0 <= x) && (0 <= y);
    public int min(int x, int y) {
        int res;
        if (x < y) { res = x; }
        else       { res = y; }
        return res;
    }
}
