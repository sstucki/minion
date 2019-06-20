package loyalty;
public class LoyaltyApp {
    //@ requires (0 <= flights) && (flights <= 100);
    public int compStatusLevel(int flights) {
        int status;
        if (flights < 10) { status = 0; }
        else if (flights < 20) { status = (flights - 10); }
        else if (flights < 30) {
            status = 0; int i = 0;
            //@ maintaining (0 <= i) && (i <= flights - 19) && (status == i * flights);
            //@ decreasing (flights - 19 - i);
            //@ assignable \strictly_nothing;
            while (i <= flights - 20) {
                status = (status + flights);
                i = i + 1; }
            if (status > 150) { status = 150; } }
        else { status = 500; }
        return status; } }
