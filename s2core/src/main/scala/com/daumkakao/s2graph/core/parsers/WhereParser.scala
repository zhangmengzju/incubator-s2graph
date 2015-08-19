package com.daumkakao.s2graph.core.parsers

import com.daumkakao.s2graph.core._
import play.api.Logger

//import com.daumkakao.s2graph.core.models.{LabelMeta, Label}

import com.daumkakao.s2graph.core.mysqls._

//import com.daumkakao.s2graph.core.models._

import com.daumkakao.s2graph.core.types2.InnerValLike

import scala.util.parsing.combinator.JavaTokenParsers

/**
 * Created by shon on 5/30/15.
 */
case class Where(val clauses: Seq[Clause] = Seq.empty[Clause]) {
  def filter(edge: Edge): Boolean = {
    clauses.map(_.filter(edge)).forall(r => r)
  }
}

abstract class Clause extends JSONParser {
  def and(otherField: Clause): Clause = And(this, otherField)

  def or(otherField: Clause): Clause = Or(this, otherField)

  def filter(edge: Edge): Boolean = ???
}

case class Equal(val propKey: Byte, val value: InnerValLike) extends Clause {
  override def filter(edge: Edge): Boolean = {
    propKey match {
      case LabelMeta.from.seq => edge.srcVertex.innerId == value
      case LabelMeta.to.seq => edge.tgtVertex.innerId == value
      case _ =>
        edge.propsWithTs.get(propKey) match {
          case None =>
            val label = edge.label
            val meta = label.metaPropsMap(propKey)
            val defaultValue = toInnerVal(meta.defaultValue, meta.dataType, label.schemaVersion)
            defaultValue == value
          case Some(edgeVal) => edgeVal.innerVal == value
        }
    }

  }
}

case class IN(val propKey: Byte, val values: Set[InnerValLike]) extends Clause {
  override def filter(edge: Edge): Boolean = {
    propKey match {
      case LabelMeta.from.seq => values.contains(edge.srcVertex.innerId)
      case LabelMeta.to.seq => values.contains(edge.tgtVertex.innerId)
      case _ =>
        edge.propsWithTs.get(propKey) match {
          case None =>
            val label = edge.label
            val meta = label.metaPropsMap(propKey)
            val defaultValue = toInnerVal(meta.defaultValue, meta.dataType, label.schemaVersion)
            values.contains(defaultValue)
          case Some(edgeVal) => values.contains(edgeVal.innerVal)
        }
    }
  }
}

case class Between(val propKey: Byte, val minValue: InnerValLike, val maxValue: InnerValLike) extends Clause {
  override def filter(edge: Edge): Boolean = {
    propKey match {
      case LabelMeta.from.seq => minValue <= edge.srcVertex.innerId && edge.srcVertex.innerId <= maxValue
      case LabelMeta.to.seq => minValue <= edge.tgtVertex.innerId && edge.tgtVertex.innerId <= maxValue
      case _ =>
        edge.propsWithTs.get(propKey) match {
          case None =>
            val label = edge.label
            val meta = label.metaPropsMap(propKey)
            val defaultValue = toInnerVal(meta.defaultValue, meta.dataType, label.schemaVersion)
            minValue <= defaultValue && defaultValue <= maxValue
          case Some(edgeVal) =>
            minValue <= edgeVal.innerVal && edgeVal.innerVal <= maxValue
        }
    }

  }
}

case class Not(val self: Clause) extends Clause {
  override def filter(edge: Edge): Boolean = {
    !self.filter(edge)
  }
}

case class And(val left: Clause, val right: Clause) extends Clause {
  override def filter(edge: Edge): Boolean = {
    left.filter(edge) && right.filter(edge)
  }
}

case class Or(val left: Clause, val right: Clause) extends Clause {
  override def filter(edge: Edge): Boolean = {
    left.filter(edge) || right.filter(edge)
  }
}

case class WhereParser(label: Label) extends JavaTokenParsers with JSONParser {

  val metaProps = label.metaPropsInvMap

  def where: Parser[Where] = rep(clause) ^^ (Where(_))

  def clause: Parser[Clause] = (predicate | parens) *
    ("and" ^^^ { (a: Clause, b: Clause) => And(a, b) } |
      "or" ^^^ { (a: Clause, b: Clause) => Or(a, b) })

  def parens: Parser[Clause] = "(" ~> clause <~ ")"

  def boolean = ("true" ^^^ (true) | "false" ^^^ (false))

  def stringLiteralWithMinus = (stringLiteral | wholeNumber | ("-" ~ stringLiteral | floatingPointNumber) ^^ {
    case _ ~ v => "-" + v
  })

  /** TODO: exception on toInnerVal with wrong type */
  def predicate = (
    (ident ~ "=" ~ ident | ident ~ "=" ~ decimalNumber | ident ~ "=" ~ stringLiteralWithMinus)^^ {
      case f ~ "=" ~ s =>
        metaProps.get(f) match {
          case None =>
            throw new RuntimeException(s"where clause contains not existing property name: $f")
          case Some(metaProp) =>
            if (f == LabelMeta.to.name) {
              Equal(LabelMeta.to.seq, toInnerVal(s, label.tgtColumnType, label.schemaVersion))
            } else if (f == LabelMeta.from.name) {
              Equal(LabelMeta.from.seq, toInnerVal(s, label.srcColumnType, label.schemaVersion))
            } else {
              Equal(metaProp.seq, toInnerVal(s, metaProp.dataType, label.schemaVersion))
            }
        }
    }
      | (ident ~ "=" ~ ident | ident ~ "=" ~ decimalNumber | ident ~ "=" ~ stringLiteralWithMinus) ^^ {
      case f ~ "!=" ~ s =>
        metaProps.get(f) match {
          case None =>
            throw new RuntimeException(s"where clause contains not existing property name: $f")
          case Some(metaProp) =>
            val wh = if (f == LabelMeta.to.name) {
              Equal(LabelMeta.to.seq, toInnerVal(s, label.tgtColumnType, label.schemaVersion))
            } else if (f == LabelMeta.from.name) {
              Equal(LabelMeta.from.seq, toInnerVal(s, label.srcColumnType, label.schemaVersion))
            } else {
              Equal(metaProp.seq, toInnerVal(s, metaProp.dataType, label.schemaVersion))
            }
            Not(wh)
        }
    }
      | (ident ~ "between" ~ ident ~ "and" ~ ident |
      ident ~ "between" ~ decimalNumber ~ "and" ~ decimalNumber |
      ident ~ "between" ~ stringLiteralWithMinus ~ "and" ~ stringLiteralWithMinus) ^^ {
      case f ~ "between" ~ minV ~ "and" ~ maxV =>
        metaProps.get(f) match {
          case None => throw new RuntimeException(s"where clause contains not existing property name: $f")
          case Some(metaProp) =>
            Between(metaProp.seq, toInnerVal(minV, metaProp.dataType, label.schemaVersion),
              toInnerVal(maxV, metaProp.dataType, label.schemaVersion))
        }
    }
      | (ident ~ "in" ~ "(" ~ rep(ident | decimalNumber | stringLiteralWithMinus | "true" | "false" | ",") ~ ")") ^^ {
      case f ~ "in" ~ "(" ~ vals ~ ")" =>
        metaProps.get(f) match {
          case None => throw new RuntimeException(s"where clause contains not existing property name: $f")
          case Some(metaProp) =>
            val values = vals.filter(v => v != ",").map { v =>
              toInnerVal(v, metaProp.dataType, label.schemaVersion)
            }
            IN(metaProp.seq, values.toSet)
        }
    }

      | (ident ~ "not in" ~ "(" ~ rep(ident | decimalNumber | stringLiteralWithMinus | "true" | "false" | ",") ~ ")") ^^ {
      case f ~ "not in" ~ "(" ~ vals ~ ")" =>
        metaProps.get(f) match {
          case None => throw new RuntimeException(s"where clause contains not existing property name: $f")
          case Some(metaProp) =>
            val values = vals.filter(v => v != ",").map { v =>
              toInnerVal(v, metaProp.dataType, label.schemaVersion)
            }
            Not(IN(metaProp.seq, values.toSet))
        }
    }
    )

  def parse(sql: String): Option[Where] = {
    parseAll(where, sql) match {
      case Success(r, q) => Some(r)
      case x =>
        Logger.error(x.toString)
        None
    }
  }
}
