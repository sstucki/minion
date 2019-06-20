package se.gu
package minion

import scala.collection.JavaConverters._

import de.uka.ilkd.key.logic.{ Name, Term, TermBuilder, TermFactory }
import de.uka.ilkd.key.logic.sort.Sort
import de.uka.ilkd.key.logic.op.{
  Function, IProgramVariable, Junctor, LogicVariable, QuantifiableVariable }


final class VarTransformer(
  val tb: TermBuilder,
  var dict: VarTransformer.Dict = Map()) {
  import VarTransformer._

  val tf: TermFactory = tb.tf

  def transform(t: Term): Term = {

    // Transform all the subexpressions and compute their free
    // variables.
    val ts = for (i <- (0 until t.arity)) yield {

      // Compute the names of all the free variables bound in this
      // subterm and add them to the dictionary (it's important that
      // we retain the original quanlified variables in this case).
      val bvs = for (qv <- t.varsBoundHere(i).asScala) yield {
        val gv = (qv.name, qv.sort)
        dict += ((gv, qv))
        gv
      }

      // Transform the i-th subexpression, then remove its bound
      // variables from the resulting dictionary of free variables.
      val t2 = transform(t.sub(i))
      dict = dict -- bvs
      t2
    }

    // Extract the variable name and sort from variable operators.
    val qv = t.op match {
      case pv : IProgramVariable                => Some((pv.name, pv.sort))
      case qv : QuantifiableVariable            => Some((qv.name, qv.sort))
      case fv : Function if fv.isSkolemConstant => Some((fv.name, fv.sort))
      case _                                    => None
    }

    // Convert all variable operators to logic variables.
    val newOp = qv match {
      case Some(v) =>
        // This operator is a variable.  Have we already created a
        // corresponding KeY logic variable?
        dict.get(v) match {
          case Some(qv) => qv // Yes.
          case None     =>    // No, translate and register in the dict.
            val qv = genToLogVar(v)
            dict += ((v, qv))
            qv
        }
      case None => t.op
    }

    tf.createTerm(newOp, ts.toArray, t.boundVars, t.javaBlock, t.getLabels)
  }
}

object VarTransformer {

  type GenVar = (Name, Sort)
  type Dict   = Map[GenVar, QuantifiableVariable]
  type FvSet  = Set[QuantifiableVariable]

  def genToLogVar(p: GenVar): QuantifiableVariable =
    new LogicVariable(p._1, p._2)

  def varsToDict(vs: FvSet): Dict =
    vs.map(v => ((v.name, v.sort), v)).toMap

  def apply(tb: TermBuilder, dict: Dict) = new VarTransformer(tb, dict)
  def apply(tb: TermBuilder) = new VarTransformer(tb, Map())
}
