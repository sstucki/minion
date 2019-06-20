package se.gu
package minion

import scala.collection.JavaConverters._
import scala.xml._

import java.io.File
import java.{ util => ju }

import de.uka.ilkd.key.java.{ Services }

import de.uka.ilkd.key.logic.op.IProgramVariable

import de.uka.ilkd.key.symbolic_execution.model.IExecutionVariable
import de.uka.ilkd.key.symbolic_execution.util.SymbolicExecutionUtil

import VarTransformer.GenVar


/**
 * An ADT representing state names/locations.
 *
 * A location is either
 *
 *  - a variable name,
 *  - a field access on a parent location, or
 *  - an array access on a parent location.
 */
sealed trait StateLocation
case class Variable(pv: GenVar) extends StateLocation
case class FieldSelection(
  parent: StateLocation, fieldName: IProgramVariable) extends StateLocation
case class ArraySelection(
  parent: StateLocation, index: Int) extends StateLocation

object StateLocation {

  /**
   * Prefix used to distinguish state variables.
   *
   * NOTE: we tag all state variables with a STATE_PREFIX to
   * distinguish them from the initial state (method parameters,
   * etc.).  See the `ProgPath.stateMap` method for more details.
   */
  val STATE_PREFIX: String = "_$"

  def apply(
    iVar: IExecutionVariable, services: Services): Option[StateLocation] = {
    val parVal = iVar.getParentValue
    if (iVar.isArrayIndex) {
      assert(parVal != null)

      val idxTerm = iVar.getArrayIndex
      val idxStr  =
        SymbolicExecutionUtil.formatTerm(idxTerm, services, false, true)

      // Try extracting the array index.  It might be a star variable
      // '*', in which case the result is undefined (i.e. we return
      // None).
      val idxOpt = try Some(Integer.valueOf(idxStr)) catch {
        case _: java.lang.NumberFormatException => None
      }

      for {
        idx <- idxOpt
        pl  <- StateLocation(parVal.getVariable, services)
      } yield ArraySelection(pl, idx)
    } else {
      val pv = iVar.getProgramVariable
      if (parVal != null) {
        val plOpt = StateLocation(parVal.getVariable, services)
        plOpt map { pl => FieldSelection(pl, pv) }
      } else {
        assert(pv != null)
        Some(Variable((pv.name, pv.sort)))
      }
    }
  }

  /**
   * Convert a state location to a variable name.
   *
   * NOTE: we rename all the state variables, adding a STATE_PREFIX
   * prefix, in order to distinguish them from the initial state.  See
   * the `ProgPath::stateMap` method below for more details.
   */
  def toVarName(l: StateLocation): String = l match {
    case Variable(pv)         => STATE_PREFIX + pv._1.toString
    case FieldSelection(l, f) => toVarName(l) + "$dot$" + f.name.toString
    case ArraySelection(l, i) =>
      toVarName(l) + "$arr$" + i.toString
  }
}
