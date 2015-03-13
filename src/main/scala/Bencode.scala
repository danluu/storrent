/* Copyright (C) 2009 Thomas Rampelberg <pyronicide@saunter.org>

 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.

 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */

/* Bencoding tools. The parser implements a full bencode grammar (string,
 * integer, list, dictionary).
 */
// XXX - NEED UNIT TESTS!!!
package org.saunter.bencode

import scala.collection.mutable.ListBuffer
import scala.util.parsing.combinator.{Parsers, ImplicitConversions}

/**
 * Most of the standard rep* methods end up using while loops at their base to
 * make sure StackOverflows don't happen. While from the command line you don't
 * get a StackOverflow, for some reason lift/jetty likes to throw them when
 * parsing simple torrents. This generator takes the recursion and makes it a
 * simple while loop.
 */
trait ParserGenerator extends Parsers {
  override def repN[T](n: Int, p: => Parser[T]): Parser[List[T]] = Parser {
    in0 =>
      val xs = new scala.collection.mutable.ListBuffer[T]
      var in = in0
      var i = n

      if (n == 0) {
        return success(List())
      }
      var res = p(in)
      while(res.successful && i > 0) {
        i -= 1
        xs += res.get
        in = res.next
        res = p(in)
      }

      res match {
        case e: Error => e
        case _ =>
          if (!xs.isEmpty) {
            Success(xs.toList, in)
          }
          else {
            Failure(res.asInstanceOf[NoSuccess].msg, in0)
          }
      }
  }
}

object BencodeDecoder extends ParserGenerator with ImplicitConversions {
  implicit def strToInput(in: String): Input =
    new scala.util.parsing.input.CharArrayReader(in.toCharArray)
  type Elem = Char

  def decode(in: String): Option[Any] =
    doc(in) match {
      case Success(v, _) => Some(v)
      case x => Some(x)
    }

  lazy val doc: Parser[Any] = number | string | list | dict

  // Numbers i-10e -> -10i
  lazy val number: Parser[Long] = 'i' ~> int <~ 'e'
  lazy val int =
    ( digits ^^ { case x => x.mkString.toLong } ) |
    ( '-' ~> digits ^^ { case x => x.mkString.toLong * -1 } )
  lazy val digits = rep1(digit)
  lazy val digit = elem("digit", c => c >= '0' && c <= '9')

  // Strings 3:foo -> foo
  lazy val string: Parser[String] =
    ('0' <~ ':' ^^ { case x => "" }
     | len >> ( stringN(_) ) )
  lazy val len = int <~ ':'
  def stringN(n: Long) =
    repN(n.toInt, char) ^^ { case x => 
        x.mkString }
  lazy val char = elem("any char", c => true)

  // Lists li1ei2ee -> [1, 2]
  lazy val list: Parser[List[Any]] = 'l' ~> rep(doc) <~ 'e'

  // Dictionaries d3:fooi1ee -> { "foo": 1 }
  lazy val dict: Parser[Map[String, Any]] =
    ( 'd' ~> members <~ 'e' ^^ { case xs => Map(xs :_*) } |
      'd' ~ 'e' ^^ { case x ~ y => Map() } )
  lazy val members = rep1(pair)
  lazy val pair: Parser[(String, Any)] = string ~ doc ^^ { case x ~ y => (x,y) }
}

object BencodeEncoder {
  /**
   * Generate a bencoded string from scala objects. This can handle the
   * entire bencoding grammar which means that Int, String, List and Map can be
   * encoded.
   */
  def encode(input: Any): String =
    input match {
      case x: Int => int(x)
      case x: Long => int(x)
      case x: String => string(x)
      case x: List[_] => list(x)
      case x: Map[String, _] => dictionary(x)
      case _ => ""
    }

  def int(input: Int): String =
    "i" + input + "e"

  def int(input: Long): String =
    "i" + input + "e"

  def string(input: String): String =
    input.length + ":" + input

  def list(input: List[_]): String =
    "l" + input.map( x => encode(x)).mkString + "e"

  def dictionary(input: Map[String, _]): String =
    "d" + input.toList.sortWith( (x,y) => x._1<y._1 ).map(
      x => (string(x._1), encode(x._2))).flatMap(
        x => x._1 + x._2 ).mkString + "e"
}
