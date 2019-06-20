package toll;

public class Toll {

  public int rate(int hour, int passengers) {
    // standard rates:
    int r;
    if (hour >= 9 && hour <= 17) {          //  - daytime
      r = 90;
    } else {                                //  - nighttime
      r = 70;
    }

    // carpool rates: 20% off
    if (passengers > 2) {
      r = r - (r / 5);
    }
    return r;
  }

  public int max(int x, int y) {
    if (x > y) { return x; }
    else       { return y; }
  }


  public int feeSimple(int t1, int t2, int p) {

    // rates at the two toll stations
    int r1 = rate(t1, p);
    int r2 = rate(t2, p);

    // fee for section between t1 and t2
    return max(r1, r2) * 4;
  }

  public int fee(int t1, int t2, int t3, int p) {

    // rates at the three toll stations
    int r1 = rate(t1, p);
    int r2 = rate(t2, p);
    int r3 = rate(t3, p);

    // fees per section (t1, t2) and (t2, t3)
    int f1 = max(r1, r2) * 4;
    int f2 = max(r2, r3) * 7;

    // total fee
    return f1 + f2;
  }
}
