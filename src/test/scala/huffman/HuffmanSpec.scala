package snappyaccl

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.math._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import BigInt.probablePrime
import java.io.File
import java.io.PrintWriter
import treadle.{TreadleTester, TreadleOptionsManager}
import chisel3._

class HuffmanSpec extends AnyFlatSpec with Matchers {
    // val s = chisel3.Driver.emit(() => new EncodeShiftBuffer)
    // implicit val tester: TreadleTester = new TreadleTester(s, new TreadleOptionsManager())
    // tester.engine.makeVCDLogger("results/treadle-CompressionAccelerator.vcd", showUnderscored = true)

    behavior of "HuffmanModule" 

    it should "Test Huffman Decoding Buffer" in {
        DoDecodeShiftBufferTest() should be(true)
    }

    it should "Test Huffman Encoding Buffer" in {
        DoEncodeShiftBufferTest() should be(true)
    }

    it should "Test Huffman Encoder" in {
        DoHuffmanEncoderTest() should be(true)
    }

    it should "Test Huffman Decoder" in {
        DoHuffmanDecoderTest() should be(true)
    }
    
}
