package org.ekrich.config.scala3

import scala.reflect.ClassTag

import org.junit.Assert.fail

object TestSupport {
  def intercept[E <: Throwable](block: => Any)(using ct: ClassTag[E]): E =
    try {
      block
      fail("expected " + ct.runtimeClass.getName + " but nothing was thrown")
      throw new AssertionError("unreachable")
    } catch {
      case e: Throwable if ct.runtimeClass.isInstance(e) => e.asInstanceOf[E]
    }
}
