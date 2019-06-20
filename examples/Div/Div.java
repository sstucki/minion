class Div {

    //@ requires x >= 0 && y > 0;
    //@ ensures (\result * y <= x) && (\result * y < x + y);
    int posDiv(int x, int y) {
        int q = 0;
        /*@ maintaining (r >= 0) && (r + q * y == x);
          @ decreasing r;
          @*/
        for (int r = x; r >= y; ++q) {
            r -= y;
        }
        return q;
    }

    /*@ requires y != 0;
      @ ensures \result == x / y;
      @*/
    int div(int x, int y) {
        int res;
        if (x < 0) {
            if (y < 0) { res =  posDiv(-x, -y); }
            else       { res = -posDiv(-x,  y); }
        } else {
            if (y < 0) { res = -posDiv(x, -y); }
            else       { res =  posDiv(x,  y); }
        }
        return res;
    }
}
