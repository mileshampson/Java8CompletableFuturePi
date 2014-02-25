import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 *  Demonstration of Java 8 completable futures for computing some number of pi digits using a very simple Chudnovsky
 *  implementation (no separation of terms, binary splitting, square root optimizations, or fraction factoring).
 *  There are a couple of rounding issues in the last few decimal places, so this is more useful for maxing out
 *  CPU cores than anything else!
 */
public class SimplePi
{
    // Use an int, assuming we do not need more than 2.7 trillion digits
    public static final int NUM_PI_DIGITS = 10000;
    // Use BigDecimal for arbitrary precision, rounding all calculations that give an irrational number to this value.
    // +2 comes from the two digits required for 3.
    private static final int NUM_DIGITS_TO_KEEP = NUM_PI_DIGITS + 2;
    private static final double CHUDNOVSKY_CONVERGENCE_RATE = Math.log(151931373056000L)/Math.log(10);
    private static final int NUM_SERIES_TERMS = (int)Math.ceil(NUM_PI_DIGITS / CHUDNOVSKY_CONVERGENCE_RATE);
    // Build a range of all the terms to calculate, to allow us to farm these out to different cores
    private static List<Integer> seriesToSum = new ArrayList<Integer>() {{
        for(int i = 0; i <= NUM_SERIES_TERMS; i += 1) {
            add(i);
        }
    }};
    // The available cores, used to execute this algorithm on multiple threads (only a benefit for smaller pi values).
    private static final int NUM_CORES = Runtime.getRuntime().availableProcessors();
    // Chudnovsky constants:
    private static final BigDecimal SIXTY40320_POW_3 = new BigDecimal("262537412640768000");
    // Calculate this after we start timing
    private static BigDecimal multAllBy;

    /**
     * Runs the program
     */
    public static void main(String... args) throws ExecutionException, InterruptedException {
        System.err.println (NUM_PI_DIGITS + " digits of pi using a " + NUM_SERIES_TERMS + " term Chudnovsky series " +
                            "converging at " + CHUDNOVSKY_CONVERGENCE_RATE + " digits/term is:");
        long startTime = System.nanoTime();
        // Calculate the constant factor for the terms
        multAllBy = new BigDecimal(1).divide(new BigDecimal(426880).multiply(bigSqrt(new BigDecimal(10005))),
                                             NUM_DIGITS_TO_KEEP, BigDecimal.ROUND_HALF_EVEN);
        long elapsedTime = System.nanoTime() - startTime;
        double seconds = (double)elapsedTime / 1000000000.0;
        System.out.println("Calculating constants took " + seconds + " seconds.");
        startTime = System.nanoTime();
        System.err.println (piChudnovskyConcurrent().toPlainString());
        elapsedTime = System.nanoTime() - startTime;
        seconds = (double)elapsedTime / 1000000000.0;
        System.out.println("Calculating pi concurrently on " + NUM_CORES + " cores took " + seconds + " seconds.");
        System.exit(0);
    }

    /**
     * This is where the magic completable futures are used.
     */
    public static BigDecimal piChudnovskyConcurrent() throws ExecutionException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_CORES);
        List<CompletableFuture<BigDecimal>> expansionFutures = seriesToSum.stream().
                map(seriesVal -> CompletableFuture.supplyAsync(() -> expandChudnovskyTerm(seriesVal), executor)).
                // Accumulate the calculated terms into a list in the order their map operation completes.
                        collect(Collectors.<CompletableFuture<BigDecimal>>toList());

        CompletableFuture<List<BigDecimal>> allDone = sequence(expansionFutures);
        CompletableFuture<Optional<BigDecimal>> piVal = allDone.thenApply(terms ->
                terms.stream().reduce((BigDecimal firstTerm, BigDecimal secondTerm) -> firstTerm.add(secondTerm))
        );
        BigDecimal totalWithCoefficient =  multAllBy.multiply(piVal.get().get());
        return new BigDecimal(1).divide(totalWithCoefficient, NUM_DIGITS_TO_KEEP, BigDecimal.ROUND_HALF_EVEN);
    }

    /**
     * Transform the list into a stream
     */
    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> allDoneFuture =
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
        return allDoneFuture.thenApply(v ->
                futures.stream().
                        map(future -> future.join()).
                        collect(Collectors.<T>toList())
        );
    }

    /**
     * Boring maths stuff follows....
     */
    private static BigDecimal expandChudnovskyTerm(final int seriesVal) {
        //  The following two lines are just a theoretically quicker Math.pow(-1, seriesVal), there could be edge cases!
        int isEvenBoolAsInt = seriesVal & 1;
        int altTerm = ~(isEvenBoolAsInt) - isEvenBoolAsInt + 2;

        BigDecimal midNumTerm = factorial(6L * seriesVal);
        // Can use long for intermediate calculation here as 9223372036854775807(the largest long) is greater than
        // 1170679523101980107 (13591409 + 545140134 * 2147483647(the largest int)).
        BigDecimal lastNumTerm = new BigDecimal(13591409 + 545140134L * seriesVal);
        BigDecimal numerator = new BigDecimal(altTerm).multiply(midNumTerm).multiply(lastNumTerm);

        BigDecimal firstDenomTerm = factorial(3L * seriesVal);
        BigDecimal secondDenomTerm = factorial(seriesVal).pow(3);
        // Use the identities a^nm = (a^n)^m, and (a^n)(a^m) = a^(n+m) to work out the last term
        BigDecimal lastDenomTerm = SIXTY40320_POW_3.pow(seriesVal);

        BigDecimal denominator = firstDenomTerm.multiply(secondDenomTerm).multiply(lastDenomTerm);

        return numerator.divide(denominator, NUM_DIGITS_TO_KEEP, BigDecimal.ROUND_HALF_EVEN);
    }
    static BigDecimal factorial(long digit) {
        BigDecimal result = new BigDecimal(1);
        for (long i=digit; i>1; i--)
        {
            result = result.multiply(new BigDecimal(i));
        }
        return result;
    }
    private static BigDecimal sqrtNewtonRaphson(BigDecimal c, BigDecimal xn, BigDecimal precision) {
        BigDecimal fx = xn.pow(2).add(c.negate());
        BigDecimal fpx = xn.multiply(new BigDecimal(2));
        BigDecimal xn1 = fx.divide(fpx,2*NUM_DIGITS_TO_KEEP,BigDecimal.ROUND_HALF_EVEN);
        xn1 = xn.add(xn1.negate());
        //----
        BigDecimal currentSquare = xn1.pow(2);
        BigDecimal currentPrecision = currentSquare.subtract(c);
        currentPrecision = currentPrecision.abs();
        if ( currentPrecision.compareTo(precision) <= -1 )
        {
            return xn1;
        }
        return sqrtNewtonRaphson(c, xn1,precision);
    }
    public static BigDecimal bigSqrt(BigDecimal c) {
        return sqrtNewtonRaphson(c,new BigDecimal(1),new BigDecimal(1).divide(new BigDecimal(10).pow(NUM_DIGITS_TO_KEEP)));
    }
}
