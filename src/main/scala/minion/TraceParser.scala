package se.gu
package minion

import java.lang.Character
import scala.util.parsing.combinator._

import de.uka.ilkd.key.java.abstraction.{
  KeYJavaType, PrimitiveType, ArrayType }
import de.uka.ilkd.key.java.declaration.ParameterDeclaration

import de.uka.ilkd.key.logic.{ Term, TermBuilder }

// FIXME: There is probably already enough parsing functionality
// implemented in KeY to make most of this redundant.

// The result type of the various trace parsers.
sealed trait TraceElem
case class BaseTm(t: Term) extends TraceElem
case class ArrayTm(ts: List[TraceElem]) extends TraceElem

trait JavaTypeParsers extends JavaTokenParsers {

  def tb: TermBuilder

  def typed(t: KeYJavaType): Parser[TraceElem] = t.getJavaType match {
    case pt : PrimitiveType =>
      pt match {
        case PrimitiveType.JAVA_SHORT   => intTerm ^^ BaseTm
        case PrimitiveType.JAVA_INT     => intTerm ^^ BaseTm
        case PrimitiveType.JAVA_CHAR    => charTerm ^^ BaseTm
        case PrimitiveType.JAVA_LONG    =>
          (intTerm <~ (accept('l') | 'L').?) ^^ BaseTm
        case PrimitiveType.JAVA_BOOLEAN => booleanTerm ^^ BaseTm
        case _ => throw new NotImplementedError(
          "Unsupported Java primitive type: " + pt.getName)
      }
    case at: ArrayType =>
      val dim = at.getDimension
      val baseType = at.getBaseType.getKeYJavaType
      arrayLiteral(baseType, dim)

    case t => throw new NotImplementedError(
      "Unsupported Java primitive type: " + t.getName)
  }

  // FIXME: this only handles decimal numerals. Extend it to
  // hexadecimal, octal and binary numerals.
  def intTerm: Parser[Term] = wholeNumber ^^ tb.zTerm

  def charLiteral: Parser[Char] =
    ( ("""\'[^"\x00-\x1F\x7F\\]""".r ^^ (_.charAt(1)))
    | ("""\'\\[\\'"bfnrt]""".r ^^ (s => s.charAt(2) match {
        case 'b' => '\b'
        case 'f' => '\f'
        case 'n' => '\n'
        case 'r' => '\r'
        case 't' => '\t'
        case c   => c
      }))
    | ("""\'\\u[a-fA-F0-9]{4}""".r ^^ { s =>
          Integer.parseUnsignedInt(s.drop(3), 16).toChar
      })) <~ '\''

  // FIXME: should use C-notation instead of Z-notation here...
  def charTerm: Parser[Term] = charLiteral ^^ (c => tb.zTerm(c.toLong))

  def booleanTerm: Parser[Term] = ("true" | "false") ^^ {
    case "true"  => tb.TRUE
    case "false" => tb.FALSE
  }

  def commaSeq[T](p: Parser[T]): Parser[List[T]] = {
    val inner = p ~ ("," ~> p).* ^^ { case a ~ as => a :: as }
    "{" ~> (inner.? ^^ (_.getOrElse(List()))) <~ "}"
  }

  def arrayLiteral(baseType: KeYJavaType, dim: Int): Parser[TraceElem] =
    if (dim <= 0) {
      throw new IllegalArgumentException("Illegal array dimension: " + dim)
    } else if (dim == 1) {
      commaSeq(typed(baseType)) ^^ ArrayTm
    } else {
      commaSeq(arrayLiteral(baseType, dim - 1)) ^^ ArrayTm
    }
}

class TraceParser(
  val tb: TermBuilder,
  params: List[ParameterDeclaration],
  returnType: KeYJavaType) extends JavaTypeParsers {

  val trace: Parser[(List[TraceElem], TraceElem)] = {
    val pp = params.foldRight(success(List[TraceElem]())) { (d, p) =>
      val t = d.getTypeReference.getKeYJavaType
      (typed(t) <~ ",") ~ p ^^ { case x ~ xs => x :: xs }
    }
    pp ~ typed(returnType) ^^ { case ps ~ r => (ps, r) }
  }
}
