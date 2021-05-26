package huffman

import chisel3._
import chisel3.util._

class ScratchpadReadIO(val n: Int, val w: Int) extends Bundle {
  val en = Output(Bool())
  val addr = Output(UInt(log2Ceil(n).W))
  val data = Input(UInt(w.W))
}

// For test memory, use SRAM2RW128x32 as memory cell. We need 4 such cells (256 lines * 64 bits).
class MemCell extends BlackBox {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val reset = Input(Reset())
        val addr1 = Input(UInt(7.W))
        val in_data1 = Input(UInt(32.W))
        val out_data1 = Output(UInt(32.W))
        val wen1 = Input(Bool())

        val addr2 = Input(UInt(7.W))
        val out_data2 = Output(UInt(32.W))
    })
} 

// This one is a smaller memory.
class MemCellSmall extends BlackBox {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val reset = Input(Reset())
        val addr = Input(UInt(7.W))
        val in_data = Input(UInt(32.W))
        val out_data = Output(UInt(32.W))
        val wen = Input(Bool())
    })
}

class SynthMem extends Module {
    val io = IO(new Bundle {
        val table_port = Vec(2, Flipped(new EncodeTablePort(11)))
        val write_en = Input(Bool())
        val write_addr = Input(UInt(8.W))
        val write_data = Input(UInt(64.W))
    })

    val mem_array = Array.fill(2) { Array.fill(2) { Module(new MemCell) } }

    for (i <- 0 until 2) {
        mem_array(i)(0).io.addr1 := Mux(io.write_en, io.write_addr(6, 0), io.table_port(0).addr(9, 3))
        mem_array(i)(0).io.addr2 := io.table_port(1).addr(9, 3)
        mem_array(i)(1).io.addr1 := Mux(io.write_en, io.write_addr(6, 0), io.table_port(0).addr(9, 3))
        mem_array(i)(1).io.addr2 := io.table_port(1).addr(9, 3)

        mem_array(i)(0).io.wen1 := io.write_en && !(io.write_addr(7) === (i.U)(0))
        mem_array(i)(1).io.wen1 := io.write_en && !(io.write_addr(7) === (i.U)(0))
        mem_array(i)(0).io.in_data1 := io.write_data(31, 0)
        mem_array(i)(1).io.in_data1 := io.write_data(63, 32)

        mem_array(i)(0).io.clock := clock
        mem_array(i)(1).io.clock := clock
        mem_array(i)(0).io.reset := reset
        mem_array(i)(1).io.reset := reset
    }

    io.table_port(1).data := Mux(RegNext(io.table_port(1).addr(10)), 
        Cat(mem_array(0)(1).io.out_data2, mem_array(0)(0).io.out_data2),
        Cat(mem_array(1)(1).io.out_data2, mem_array(1)(0).io.out_data2)
    )
    io.table_port(0).data := Mux(RegNext(io.table_port(0).addr(10)), 
        Cat(mem_array(0)(1).io.out_data1, mem_array(0)(0).io.out_data1),
        Cat(mem_array(1)(1).io.out_data1, mem_array(1)(0).io.out_data1)
    )
}

class SynthMemSmall extends Module {
    val io = IO(new Bundle {
        val table_port = Flipped(new EncodeTablePort(11))
        val write_en = Input(Bool())
        val write_addr = Input(UInt(8.W))
        val write_data = Input(UInt(64.W))
    })

    val mem_array = Array.fill(2) { Module(new MemCellSmall) }

    mem_array(0).io.addr := Mux(io.write_en, io.write_addr(6, 0), io.table_port.addr(9, 3))
    mem_array(1).io.addr := Mux(io.write_en, io.write_addr(6, 0), io.table_port.addr(9, 3))

    mem_array(0).io.wen := io.write_en
    mem_array(1).io.wen := io.write_en
    mem_array(0).io.in_data := io.write_data(31, 0)
    mem_array(1).io.in_data := io.write_data(63, 32)

    mem_array(0).io.clock := clock
    mem_array(1).io.clock := clock
    mem_array(0).io.reset := reset
    mem_array(1).io.reset := reset

    io.table_port.data := Cat(mem_array(1).io.out_data, mem_array(0).io.out_data)
}

class Top extends Module {
    val io = IO(new Bundle {
        // Exposed port for Huffman IO
        val req = Flipped(Decoupled(new HuffmanEncodeRequest))
        val sp_read = new ScratchpadReadIO((1 << 16) - 1, 8)
        val resp = Valid(UInt(8.W))
        // Are we encoding or decoding?
        val encoding = Input(Bool())
        // Memory write port for testbench
        val write_en = Input(Bool())
        val write_addr = Input(UInt(8.W))
        val write_data = Input(UInt(64.W))
        val write_order_table = Input(Bool())
        // Register write
        val csr_write = Input(Bool())
        val csr_addr = Input(UInt(2.W))
        val csr_data = Input(UInt(11.W))
    })

    val csr = Reg(Vec(4, UInt(11.W)))

    val order_table_reg = csr(0) // One entry = 1 byte, 256 entries
    val code_table_reg = VecInit(csr(2), csr(1)) // For each code supertable (64 tables), one table=8 entires, one entry=1 byte
    val table_idx_table_reg = csr(3) // One table = 16 entries, one entry = 1 byte, 64 tables

    when (io.csr_write) {
        csr(io.csr_addr) := io.csr_data
    }

    val encoder = Module(new HuffmanEncoder(11, 16))
    val decoder = Module(new HuffmanDecoder(11, 16))

    val table = Module(new SynthMem)
    val order_table = Module(new SynthMemSmall)

    order_table.io.write_addr := io.write_addr
    order_table.io.write_data := io.write_data
    order_table.io.write_en := io.write_en && io.write_order_table

    table.io.write_en := io.write_en && !io.write_order_table
    table.io.write_addr := io.write_addr
    table.io.write_data := io.write_data

    encoder.io.order_table := order_table_reg
    encoder.io.code_table := code_table_reg
    encoder.io.table_idx_table := table_idx_table_reg
    decoder.io.order_table := order_table_reg
    decoder.io.code_table := code_table_reg
    decoder.io.table_idx_table := table_idx_table_reg

    decoder.io.sp_read.data := 0.U
    decoder.io.req.bits.head := 0.U
    decoder.io.req.bits.length := 0.U
    decoder.io.req.valid := false.B
    decoder.io.table_port(1).data := 0.U
    decoder.io.table_port(0).data := 0.U
    decoder.io.order_table_port.data := 0.U

    encoder.io.sp_read.data := 0.U
    encoder.io.req.bits.head := 0.U
    encoder.io.req.bits.length := 0.U
    encoder.io.req.valid := false.B
    encoder.io.table_port(1).data := 0.U
    encoder.io.table_port(0).data := 0.U
    encoder.io.order_table_port.data := 0.U

    when (io.encoding) {
        encoder.io.req <> io.req
        encoder.io.resp <> io.resp
        encoder.io.sp_read <> io.sp_read
        io.sp_read.addr := encoder.io.sp_read.addr
        encoder.io.table_port <> table.io.table_port
        encoder.io.order_table_port <> order_table.io.table_port
    } .otherwise {
        decoder.io.req <> io.req
        decoder.io.resp <> io.resp
        decoder.io.sp_read <> io.sp_read
        io.sp_read.addr := decoder.io.sp_read.addr
        decoder.io.table_port <> table.io.table_port
        decoder.io.order_table_port <> order_table.io.table_port
    }

}

import chisel3.stage.ChiselStage
object VerilogMain extends App {
  (new ChiselStage).emitVerilog(new Top)
}
