package credit;
public class CreditApp {
    //@ requires (0 <= incidents) && (incidents <= 3) && (1 <= tax)  && (tax <= 3);
    public int compCreditScore(int incidents, int tax) {
        int creditScore;
        if (incidents == 0) {
            if (tax == 3) { creditScore = 2; }
            else { creditScore = 1; }
        }
        else if (incidents == 1) { creditScore = 1; }
        else { creditScore = 0; }
        return creditScore;
    }
}
