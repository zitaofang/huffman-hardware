package snappyaccl

import chisel3._
import chisel3.util._

import chisel3.iotesters._

import java.io.FileInputStream

// This unit test is for the shift register in the encoder. It's for debug only and disabled by default.
class EncodeShiftBufferTest(c: EncodeShiftBuffer) extends PeekPokeTester(c) {
    // Generate 10 bits of random data (left align for those less than 4 bits)
    // Input "1010", "1100", "1100", "1111", "1000", "1000"
    val data = Array(10, 12, 12, 15, 8, 8)
    // The length code is (4 - len). For 4-bit data, this variable should be 0.
    val len = Array(0, 0, 2, 0, 0, 3)
    val valid = Array(false, false, true, false, true, false)
    // Output "10101100", "11111110"
    val out = Array(0, 0, 172, 0, 254, 0)

    poke(c.io.flush, false)
    poke(c.io.in_en, true)
    
    for (i <- 0 until data.length) {
        // If we are in the last cycle, set flush to true to check if it will interfere with in_en
        if (i == data.length - 1) poke(c.io.flush, true)
        poke(c.io.shift_in, data(i))
        poke(c.io.len_code, len(i))
        step(1)
        expect(c.io.out.valid, valid(i), "Valid check failed during shift: i=" + i)
        if (valid(i)) expect(c.io.out.bits, out(i))
    }

    // Lower in_en now and see if flush will work correctly
    poke(c.io.in_en, false)
    expect(c.io.out.valid, true, "Flush valid check failed")
    // Expecting "00100000"
    expect(c.io.out.bits, 32, "Output doesn't match")

    step(1)
}

// This unit test is for the shift register in the decoder. It's for debug only and disabled by default.
// This test is not currently producing the correct result.
class DecodeShiftBufferTest(c: DecodeShiftBuffer) extends PeekPokeTester(c) {
    // Input data: 10001010, 10111100, 00010011, 00100100
    val data = Array(138, 188, 19, 36)
    val refill_val = Array(false, true, true, true, true, true, false, false, false, true, false)
    val shift_val = Array(true, false, true, false, true, true, true, true, true, true, true)
    val shift = Array(3, 0, 3, 0, 3, 2, 1, 0, 2, 3, 3)

    // This input data will never fill the buffer fully, so refill ready should always be high
    // Output reference: N/A, 1000, 1010, 1010, 1011, 1110, 1000, 0000, 0010, 0110, 0010
    val refill_ready = Array(true, true, true, true, false, false, false, false, false, true, true)
    val out = Array(0, 8, 10, 10, 11, 14, 8, 0, 2, 6, 4)
    val out_val = Array(false, true, true, true, true, true, true, true, true, true, true)

    poke(c.io.flush, false)
    poke(c.io.refill.valid, false)
    step(1)

    var data_i = 0
    
    for (i <- 0 until refill_val.length) {
        poke(c.io.refill.valid, refill_val(i))
        expect(c.io.refill.ready, refill_ready(i), "at i=" + i)
        // Load data in if consumed
        if (refill_val(i) && refill_ready(i)) {
            poke(c.io.refill.bits, data(data_i))
            data_i = data_i + 1
        }
        // Load shift value
        poke(c.io.shift.valid, shift_val(i))
        poke(c.io.shift.bits, shift(i))
        step(1)
        // Check output
        expect(c.io.out.valid, out_val(i), "at i=" + i)
        if (out_val(i)) expect(c.io.out.bits, out(i), "at i=" + i)
    }

    // Check flush
    poke(c.io.flush, true)
    poke(c.io.shift.valid, false)
    step(1)
    expect(c.io.out.valid, false)

    // Check full behavior
    poke(c.io.flush, false)
    poke(c.io.refill.valid, true)
    poke(c.io.shift.valid, false)
    step(2)
    expect(c.io.refill.ready, false, "Buffer ready check at full")
}

class HuffmanTest[+T <: HuffmanModule](c: T, testingEncoder: Boolean) extends PeekPokeTester(c) {
    println("Working Directory = " + java.lang.System.getProperty("user.dir"));
    var cycles = 0

    // Read tree lookup table
    val table_stream = new FileInputStream("tmp/table.dat")
    val table_array = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var read_data: Int = 0

    read_data = table_stream.read()
    while (read_data != -1) {
        table_array.append(read_data.byteValue)
        read_data = table_stream.read()
    }
    table_stream.close()

    println("IO Done")

    val next_table_base = 0
    val upper_table_base = 16 * 64
    val lower_table_base = 16 * 64 + 8 * 64
    val canonical_table_base = (if (testingEncoder) 0 else 256) + 16 * 64 + 2 * 8 * 64

    // Read source
    val data_stream = new FileInputStream("tmp/sample_data.txt")
    val data_array = scala.collection.mutable.ArrayBuffer.empty[Byte]
    read_data = data_stream.read()
    while (read_data != -1) {
        data_array.append(read_data.byteValue)
        read_data = data_stream.read()
    }
    data_stream.close()

    println("IO Done")

    // Read reference
    val ref_stream = new FileInputStream("tmp/ref_data.dat")
    val ref_array = scala.collection.mutable.ArrayBuffer.empty[Byte]
    read_data = ref_stream.read()
    while (read_data != -1) {
        ref_array.append(read_data.byteValue)
        read_data = ref_stream.read()
    }
    ref_stream.close()

    println("IO Done")

    // Poke constants
    poke(c.io.canon_table, canonical_table_base)
    poke(c.io.code_table(1), upper_table_base)
    poke(c.io.code_table(0), lower_table_base)
    poke(c.io.table_idx_table, next_table_base)

    step(1)

    // Wait until ready
    poke(c.io.req.valid, true)
    poke(c.io.req.bits.head, 0)
    poke(c.io.req.bits.length, data_array.length)
    while(peek(c.io.req.ready) == 0 && cycles < 100) {
        step(1)
        cycles = cycles + 1
    }
    step(1)
    poke(c.io.req.valid, false)

    // Select input array according to the object
    val input_array = if (testingEncoder) data_array else ref_array
    val compare_array = if (testingEncoder) ref_array else data_array

    // Define array compliation
    def get_table(addr: Int): BigInt = {
        val base_addr = (addr >> 3) << 3
        // println("Addr: " + addr)
        // println("Content: " + table_array(addr))
        // assert(addr != 0x86f)
        (0 until 8).reverse.foldLeft(BigInt(0)) {
            (r, i) => (r << 8) + table_array(i + base_addr)
        }
    }

    // Define Loop handling process
    var data_i = 0
    while(peek(c.io.req.ready) == 0 && cycles < 10000) {
        // Process table lookup
        poke(c.io.table_port(1).data, get_table(peek(c.io.table_port(1).addr).intValue))
        poke(c.io.table_port(0).data, get_table(peek(c.io.table_port(0).addr).intValue))
        // Process source lookup
        val input_index = peek(c.io.sp_read.addr).intValue
        poke(c.io.sp_read.data, if (input_index >= input_array.length) 0 else input_array(input_index))
        // Store output
        if (peek(c.io.resp.valid) == 1) {
            if (data_i >= compare_array.length) {
                println("Too many output data: should be " + compare_array.length)
                fail
                data_i = 10000
            } else {
                val ref: Int = if (compare_array(data_i) >= 0) compare_array(data_i) else 256 + compare_array(data_i)
                expect(c.io.resp.bits, ref, "Output mismatch at " + data_i)
                data_i = data_i + 1
            }
        }
        // Step the simulation
        step(1)
        cycles = cycles + 1
    }

    // Read reference and compare
    if (compare_array.length > data_i) {
        println("Output data length too short: expected " + compare_array.length + ", got " + data_i)
        fail
    }
    // println(table_array.toString())
    println("Last output: " + compare_array(compare_array.length - 1))
}

object DoEncodeShiftBufferTest {
    def apply(): Boolean = {
        chisel3.iotesters.Driver.execute(TesterArgs() :+ "EncodeShiftBuffer", () => new EncodeShiftBuffer) {
            (c) => new EncodeShiftBufferTest(c)
        }
    }
}

object DoDecodeShiftBufferTest {
    def apply(): Boolean = {
        chisel3.iotesters.Driver.execute(TesterArgs() :+ "DecodeShiftBuffer", () => new DecodeShiftBuffer) {
            (c) => new DecodeShiftBufferTest(c)
        }
    }
}

object DoHuffmanEncoderTest {
    def apply(): Boolean = {
        chisel3.iotesters.Driver.execute(TesterArgs() :+ "HuffmanEncoder", () => new HuffmanEncoder(table_addr_width=32, sp_addr_width=16)) {
            (c) => new HuffmanTest(c, true)
        }
    }
}

object DoHuffmanDecoderTest {
    def apply(): Boolean = {
        chisel3.iotesters.Driver.execute(TesterArgs() :+ "HuffmanDecoder", () => new HuffmanDecoder(table_addr_width=32, sp_addr_width=16)) {
            (c) => new HuffmanTest(c, false)
        }
    }
}
