Minion – a gray-box monitor for data minimality
===============================================

Minion is a proof-of-concept gray-box monitor for data minimization.

The monitor uses the symbolic execution API and the SMT backend of the
[KeY deductive verification system](https://www.key-project.org/)
to extract logical characterizations of Java programs, extends them to first-order formulas over sets of observed
traces, and checks the result using the state-of-the-art [SMT solver
Z3](https://github.com/Z3Prover/z3).

Minion is written in [Scala](https://www.scala-lang.org/) and provides a simple command-line interface (CLI).


## Setup

`minion` is written in Scala.  To build it, you will need

 - a Java Runtime Environment, v.1.8 or later
 - the [Scala Build Tool (SBT)](https://www.scala-sbt.org/download.html), v.1.1.1 or later.

The easiest way to get these is to follow the [SBT download instructions](https://www.scala-sbt.org/download.html).  If you're on a Mac and using Homebrew, the following should do the trick:

```sh
$ brew install sbt@1
```

You will also need an SMT solver.  Currently, only [Z3](https://github.com/Z3Prover/z3) is supported.  The [Z3 Github page](https://github.com/Z3Prover/z3) contains some build and installation instructions.  Alternatively, you can install a prepackaged version for your OS.  On a Mac, you can use the Homebrew formula `z3`, i.e. run

```sh
$ brew install z3
```

## Usage

The easiest way to run the `minion` tool is through SBT.  E.g. change to the `minion` directory, then run

```sh
$ sbt "run -h"
```

which should print some usage info.  Note the quotes `"` around the `run` command and its arguments.  Without these quotes, SBT will not pass the `-h` option to `minion` but interpret it as an SBT option.  It is important to enclose all command-line arguments for `minion` in quotes, e.g. to run the tool on some example files, use

```sh
$ sbt "run examples/Const/Const.java examples/Const/Const.in.OK.txt"
```

If you want to invoke the tool repeatedly, you may want to run it from within an SBT console to avoid the startup time overhead caused by SBT.  To do so, run `sbt` in the `minion` directory.  After some setup messages, you should see a prompt like `sbt:Minion>`.  Then you can start the tool by invoking the `run` command, like so:

```text
sbt:Minion> run examples/Const/Const.java examples/Const/Const.in.OK.txt
```

If you don't like staring an SBT file, you can instead use the `minion` shell-script included in the repository.  Before you can use the script, you first need to generate a "fat" jar by running

```sh
$ sbt assembly
```

in the `minion` directory.  After that, you can invoke the script to run `minion`.  For example

```sh
$ ./minion examples/Const/Const.java examples/Const/Const.in.OK.txt
```

When invoked with the `-h` option, `minion` will print a description of the available command line options.

```sh
$ ./minion -h
Usage: se.gu.minion [OPTIONS] source-file [files ...]
Options:
 -h --help               print this message and exit
 -d --depth  DEPTH       set maximal path depth to DEPTH
 -e --eager              monitor eagerly
 -l --lazy               monitor lazily
 -m --method [CLS::]MTD  extract method MTD of class CLS
    --mono               check for monolithic minimality
 -o --output FILE        extract method to XML file FILE
 -u --unroll-loops       unroll loops (don't apply invariants)
 -s --solver NAME        use SMT solver NAME (CVC4, Z3)
 -t --timers             print timing info
```

The first file name after the command line options should always be a Java source file.  All subsequent files are trace input files.  If no trace files are specified, or if `-` is used as a trace file, traces will be read from standard input.  This can be used for online monitoring.

## Examples

There are some example Java programs (and traces) in the `examples` directory.

### Example 1: integer addition

Integer addition is distributed minimal but not monolithic minimal.  This can be illustrated by monitoring the traces in `examples/Add/Add.in.dist.OK.txt`:

```sh
$ sbt "run examples/Add/Add.java examples/Add/Add.in.dist.OK.txt"
```

which should terminate successfully (no violations found):

```text
Reading traces from file 'examples/Add/Add.in.dist.OK.txt'...
Parsed trace { 1, 2, 3 }.
Checking trace consistency... OK.

...

Checking values for parameter(s) 'y'...
 y = (8, 3) -> OK.
 y = (8, 4) -> OK.
 y = (8, 5) -> OK.
 y = (8, 2) -> OK.
 y = (3, 4) -> OK.
 y = (3, 5) -> OK.
 y = (3, 2) -> OK.
 y = (4, 5) -> OK.
 y = (4, 2) -> OK.
 y = (5, 2) -> OK.
done.
```

Re-running `minion` with the `--mono` flag will switch to monitoring monolithic minimality and should therefore report a violation:

```text
$ sbt "run --mono examples/Add/Add.java examples/Add/Add.in.dist.OK.txt"

...

Checking values for parameter(s) 'x', 'y'...
 x = (1, 4), y = (2, 5) -> OK.
 x = (1, 1), y = (2, 8) -> OK.
 x = (4, 1), y = (5, 8) -> non-minimal!
DONE: method 'add' non-minimal for 'x = (4, 1), y = (5, 8)'
...
```

### Example 2: constant function

The Java program `examples/Const/Const.java` implements a method `compConst` that returns its first argument `x` unchanged and is constant in its second argument `y`:

```java
public int compConst(int x, int y) {
  int res = x;
  return res;
}
```

Clearly `compConst` is neither distributed nor monolithic minimal.  But, perhaps surprisingly, there are traces where `minion` will detect a violation of distributed minimality whereas it will not detect a violation of monolithic minimality.  Consider the following set of traces  from `examples/Const/Const.in.fail.txt`:

```text
1,2,1
2,2,2
3,2,3
4,3,4
5,4,5
```

When monitoring for distributed minimality, `minion` will detect a violation given the above traces.

```text
$ sbt "run examples/Const/Const.java examples/Const/Const.in.nonmin.txt"
...
Checking values for parameter(s) 'y'...
 y = (3, 2) -> non-minimal!
DONE: method 'compConst' non-minimal for 'y = (3, 2)'
...
```

because, for any choice of value of `x`, the method `compConst` will compute the same value for `y = 3` and for `y = 2`, i.e. `compConst(x, 3) != compConst(x, 2)`.

Note that this situation does not appear among the observed traces: whenever the respective values for `y` differ between two traces, so do the respective values of `x`.  Instead, the proof/counterexample value for `x` has to be found using the SMT solver.

When monitoring for *monolithic* minimality, however, the SMT solver need not guess any parameters.  Consequently, `minion` will *not* detect a violation of monolithic minimality.  For the above set of traces.
```text
$ sbt "run --mono examples/Const/Const.java examples/Const/Const.in.nonmin.txt"
...
Checking values for parameter(s) 'x', 'y'...
 x = (4, 1), y = (3, 2) -> OK.
 x = (4, 3), y = (3, 2) -> OK.
 x = (4, 2), y = (3, 2) -> OK.
 x = (4, 5), y = (3, 4) -> OK.
 x = (1, 3), y = (2, 2) -> OK.
 x = (1, 2), y = (2, 2) -> OK.
 x = (1, 5), y = (2, 4) -> OK.
 x = (3, 2), y = (2, 2) -> OK.
 x = (3, 5), y = (2, 4) -> OK.
 x = (2, 5), y = (2, 4) -> OK.
done.
...
```

However, there is a way around this limitation: using the `--eager` flag, `minion` will not just use observed traces, but any *combination* of observed input values – even if that combination of input values does not correspond to any observed trace.  E.g. given the above input traces, `minion` is able to find a violation in eager mode.

```text
sbt "run --mono --eager examples/Const/Const.java examples/Const/Const.in.nonmin.txt"
...
Checking values for parameter(s) 'x', 'y'...
 x = (1, 2), y = (2, 2) -> OK.
 x = (1, 3), y = (2, 2) -> OK.
 x = (1, 4), y = (2, 2) -> OK.
 x = (1, 1), y = (2, 3) -> non-minimal!
DONE: method 'compConst' non-minimal for 'x = (1, 1), y = (2, 3)'
```

### Example 3: division

The Java program `examples/Div/DivNoConds.java` implements a naive version of (positive) integer division in the method `posDiv`.

```java
int posDiv(int x, int y) {
  int q = 0;
  for (int r = x; r >= y; ++q) {
    r -= y;
  }
  return q;
}
```

The method divides the positive integer `x` by the strictly positive integer `y` by repeatedly subtracting `y` from `x` and counting how many times this is possible.  Since the example involves a `for` loop that may be executed an arbitrary number of times, we cannot extract a complete logical characterization of `posDiv` automatically.  Instead we have two options:

 1. unroll the loop down to a fixed depth to obtain an approximate specification;
 2. annotate the loop with a loop invariant, which will allow KeY's symbolic execution engine to merge the different execution paths and give a complete characterization of the method.

The first option is implemented in `minion` using the `-u` or `--unroll` command-line option.  Consider the following execution traces from `examples/Div/Div.in.unroll.OK.txt`:

```text
1,1,1
2,1,2
3,1,3
...
12,1,12
```

Using the `-u` option, `minion` will accept this sequence of traces as monolithic minimal.

```
$sbt "run -u --mono -m posDiv examples/Div/DivNoConds.java examples/Div/Div.in.unroll.OK.txt"
...
Processing symbolic execution paths...
WARNING: encountered prematurely terminated path.
A path has been cut off before it was properly terminated because the maximum path depth was reached (100 execution steps).
Are you using the '-u' option? If not, consider increasing the execution depth using the '-d' option.
Proceeding with remaining execution paths................................... done.
...
 x = (5, 3) -> OK.
 x = (5, 7) -> OK.
 x = (3, 7) -> OK.
Not enough values to check parameter(s) 'y'.
done.
```

Note the warning about cut-off execution paths: by default, `minion` will explore symbolic execution trees to a depth of 100 nodes (this can be adjusted using the `-d` flag).  Since the `x` values in the above traces are all far below the cut-off limit, the unrolled loop will cover all of these traces.  Indeed, if we were to add the following pair of traces (from `examples/Div/Div.in.unroll.fail.txt`), a violation of monolithic minimality would be detected:

```
10,2,5
11,2,5
```

Note however, that symbolic execution, extraction of the program paths, and monitoring become quite computationally intensive, even for this tiny set of traces.

If we add a single trace with a large `x` value (and retaining `y = 1`), then `minion` will no longer be able to check the entire set of traces.  In particular, it will not detect that the following set of traces

```text
400,2,400
401,2,400
```

is both inconsistent (i.e. the specified return values are different from those computed by `posDiv`) and non-minimal.

```text
$sbt "run -u --mono -m posDiv examples/Div/DivNoConds.java examples/Div/Div.in.unroll.falseneg.txt"
...
Parsed trace { 400, 2, 400 }.
Checking trace consistency... OK.
Not enough values to check parameter(s) 'x', 'y'.
Parsed trace { 401, 2, 400 }.
Checking trace consistency... OK.
Checking values for parameter(s) 'x', 'y'...
 x = (400, 401), y = (2, 2) -> OK.
done.
```

The monitor fails to detect the inconsistency of the traces, as well as non-minimality of `posDiv` with respect to these traces, because the logical characterization resulting from unrolling is an over-approximation.

We can avoid such false negatives by specifying a loop invariant instead of unrolling the loop.  Consider the following variant of `posDiv` from `examples/Div/Div.java`:

```java
//@ requires x >= 0 && y > 0;
//@ ensures (\result * y <= x) && (\result * y < x + y);
int posDiv(int x, int y) {
  int q = 0;
  /*@ maintaining (r >= 0) && (r + q * y == x);
    @ decreasing r;
    @ assignable r, q;
    @*/
  for (int r = x; r >= y; ++q) {
    r -= y;
  }
  return q;
}
```

Thanks to the loop invariant, the `-u` option is no longer needed, symbolic execution terminates quickly and without cutting of any branches, and `minion` is able to establish that the pathological set of traces above is inconsistent.

```text
$sbt "run --mono -m posDiv examples/Div/Div.java examples/Div/Div.in.unroll.falseneg.txt"
...
Parsed trace { 400, 2, 400 }.
Checking trace consistency... failed!
ERROR: read inconsistent trace from file 'examples/Div/Div.in.unroll.falseneg.txt'.
```


## Known issues and limitations

 * The only SMT solver supported for now is Z3 (due to upstream issues with the KeY API).
 * Only primitive types are supported as argument and return types (i.e. no arrays or class types).  There is limited support for parsing arrays and using them as result types (though not for parameters) but this has not been thoroughly tested.
 * Programs are limited by the theories supported by the SMT solver.  E.g. be careful when using non-linear integer arithmetic, division, etc.

For details and to report additional issues, see the [issue tracker on Github](https://github.com/sstucki/minion/issues).
