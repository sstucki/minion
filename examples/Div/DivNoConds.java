class DivNoConds {

    int posDiv(int x, int y) {
        int q = 0;
        for (int r = x; r >= y; ++q) {
            r -= y;
        }
        return q;
    }

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
