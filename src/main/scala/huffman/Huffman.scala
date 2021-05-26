package huffman

import scala.math.pow

import chisel3._
import chisel3.util._

// Idea: leave tree building to software. Don't bother implementing a hardware heap.

// Design: The Snappy encoding step will stop right before bit packing. 
// If Huffman code is to be used, it will use DMA to write the frequency
// into the memory. Each frequency is 15 bits (So the max size of a block
// is 32K), with highest bit as valid bits. It takes 64 cycles to complete transaction.
// (Note that the software can start adding freq to a priority queue during DMA access)

// Then, software execute an instruction to start encoding.

class EncodeShiftBuffer extends Module {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val in_en = Input(Bool())
        val shift_in = Input(UInt(4.W))
        val len_code = Input(UInt(2.W))
        val out = Valid(UInt(8.W))
    })

    val buffer_reg = RegInit(0.U(12.W))
    val valid_reg = RegInit(0.U(12.W))

    val out_valid = valid_reg(11, 8).orR
    val out_shamt = PriorityEncoder(Reverse(valid_reg(11, 8)))
    val out_bits = (buffer_reg << out_shamt)(11, 4)
    // Mask away bits already sent
    val valid_out_mask = ~(Cat(Fill(8, out_valid), 0.U(4.W)) >> out_shamt)

    // Flush logic: If in_en or output valid is high, flush will be ignore. 
    // Flush must be held high until output valid is lowered.
    val flush_valid = valid_reg(7, 0).orR && io.flush && ~io.in_en && ~out_valid
    val flush_shamt = PriorityEncoder(Reverse(valid_reg(7, 0)))
    val flush_output = (buffer_reg << flush_shamt)(7, 0)

    io.out.valid := out_valid || flush_valid
    io.out.bits := Mux(out_valid, out_bits, flush_output)

    when (io.in_en) {
        buffer_reg := Cat(buffer_reg(7, 0), io.shift_in) >> io.len_code
        valid_reg := Cat((valid_out_mask & valid_reg)(7, 0), 15.U(4.W)) >> io.len_code
    } .elsewhen (flush_valid) {
        buffer_reg := 0.U
        valid_reg := 0.U
    } .elsewhen (out_valid) {
        valid_reg := valid_out_mask & valid_reg
    }

}

class HuffmanEncodeRequest extends Bundle {
    val head = UInt(32.W)
    val length = UInt(32.W)
}

class EncodeTablePort(val table_addr_width: Int) extends Bundle {
    val addr = Output(UInt(table_addr_width.W))
    val data = Input(UInt(64.W))
}

class HuffmanIO(val table_addr_width: Int, val sp_addr_width: Int) extends Bundle {
    val table_port = Vec(2, new EncodeTablePort(table_addr_width))
    // This port is specifically for symbol -> order. 
    val order_table_port = new EncodeTablePort(table_addr_width)

    val order_table = Input(UInt(table_addr_width.W)) // One entry = 1 byte, 256 entries
    val code_table = Input(Vec(2, UInt(table_addr_width.W))) // For each code supertable (64 tables), one table=8 entires, one entry=1 byte
    val table_idx_table = Input(UInt(table_addr_width.W)) // One table = 16 entries, one entry = 1 byte, 64 tables

    val req = Flipped(Decoupled(new HuffmanEncodeRequest))
    val sp_read = new ScratchpadReadIO(1 << sp_addr_width, 8)
    val resp = Valid(UInt(8.W))
}

// New encoding & decoding design:
// We have a canonical huffman encoding table to map the original symbol to 
// its canonical form; if we are already canonical, skip this step.
// (Canonical symbol: if 0 is the left branch and 1 is the right branch in the 
// tree, the leftmost leaf node will have a canonical symbol 0. It's very similar
// to BST rank, although this is not a BST)

// We group every four bits in the encoding together. For every of the 16 entries,
// store the max canonical symbol under the current tree. If we have a leave node,
// set all entries representing the nonexisting children of the leave node 
// to the canonical symbol.

// The entry is 16-bit wide, the lower 8 bits store the symbol as specified above. 
// The higher 8 bits store the table index (every table is of size 16). This keep the
// table representation compact. If we are at a leaf node, the table index will be 0,
// the root table. We will never go back to root table from any other table, so it's
// used to indicate a leaf node.

// Get 16 8-bit comparators. Two priority encoders, one for comparator output and one for
// reversed output. 

// Software needs to prepare the list of all symbol, in their order from left to right.
// Calculate symbol for every layer. 

// For decoding, use the 4 bits in the current group to index into the table for the 
// next layer's table. If the next level of table is 0, return the canonical symbol.
// We run the canonical symbol through the LUT and convert back to original symbol.

// Encoding requires two 64-bit ports. 

// Table format: Two 8*256 bits table for Canonical symbol translation (encode/decode).
// A series of 8*16 tables for max canonical symbols of each branch. The higher 8 entries and
// the lower 8 entries are stored separately as a 64-bit line in a larger table.
// A series of 8*16 tables for next table index, with regular table layout. 
// The big table should have 6 index bits (64 tables max) to fit in four table. 

// Table are stored in a separate SRAM with two bank. Software prepares the table and
// write the content into SRAM with a special instruction (since we only need to support
// external write). 

// For variable-length symbol (not exactly 8 bits), use exactly the same structure. 
// For those more than 8 bits (like the 288-symbol tree used by DEFLATE), we will store
// the cutoff table and the cutoff node since which we will start encountering extended table. 
// The symbols in common algorithms with Huffman encoding have at most 704 symbols, so we can have
// at most 2 cutoff registers. We check against these cutoff at every step when we go through
// one table during encoding or decoding. 
// We will store the 9th or 10th bits of the canonical table as a dense, separate table.
// (Max # of Huffman symbols: DEFLATE=288, ZSTD=256, Brotli=704). 

// Shared Huffman module base class (for testbench reusing)
class HuffmanModule(table_addr_width: Int, sp_addr_width: Int) extends Module {
    val io = IO(new HuffmanIO(table_addr_width, sp_addr_width))
}

// Encoder for static (or generated) Huffman tree. 
class HuffmanEncoder(table_addr_width: Int, sp_addr_width: Int) extends HuffmanModule(table_addr_width, sp_addr_width) {
    // States
    // When we receive a start request at s_ready, read the first symbol.
    // At s_symbol_read, convert symbol to canonical symbol and read the next symbol in.
    // At s_lut_read, read the max canonical symbol for every 4th-gen children.
    // At s_encode, write the 4-bit encoding to sp, read the next table index.

    io.sp_read.en := false.B
    io.sp_read.addr := DontCare

    val addr_reg = Reg(UInt(32.W))
    val len_reg = RegInit(0.U(32.W))

    val order_reg = Reg(UInt(8.W))
    val table_idx_reg = Reg(UInt(6.W)) // We support 64 tables at most
    val table_idx = Wire(UInt(6.W))
    table_idx := 0.U

    val buffer = Module(new EncodeShiftBuffer)

    // Output logic
    io.resp := buffer.io.out
    buffer.io.in_en := false.B
    buffer.io.shift_in := DontCare
    buffer.io.len_code := DontCare

    // Symbol comparison logic
    val max_symbols = Wire(UInt(128.W))
    max_symbols := 0.U
    val max_symbols_vec = Wire(Vec(16, UInt(8.W)))
    max_symbols_vec := max_symbols.asTypeOf(max_symbols_vec)
    val symbols_eq = Wire(Vec(16, Bool()))
    val symbols_le = Wire(Vec(16, Bool()))
    for (i <- 0 until 16) {
        symbols_eq(i) := order_reg === max_symbols_vec(i)
        symbols_le(i) := order_reg <= max_symbols_vec(i)
    }
    val symbol_end_left = PriorityEncoder(symbols_eq)
    val symbol_end_right = PriorityEncoder(symbols_eq.reverse)
    val symbol_mask = symbol_end_left ^ symbol_end_right
    val code_len = PriorityEncoder(symbol_mask.asTypeOf(UInt(4.W)))
    val match_child = PriorityEncoder(symbols_le)
    val code_len_reg = RegNext(code_len)
    val match_child_reg = RegNext(match_child)

    // IO Initialization
    io.table_port(1).addr := 0.U
    io.table_port(0).addr := 0.U
    io.order_table_port.addr := 0.U

    // Buffer function
    def pipeline_buffer(input: UInt, control: Bool) = {
        val pipe_buffer_reg = Reg(input.cloneType)
        when (control) {
            pipe_buffer_reg := input
        }
        Mux(control, input, pipe_buffer_reg)
    }

    // ===================
    // Loop control register
    val loop_busy_reg = RegInit(false.B)
    val lut_request_reg = RegInit(false.B)
    val s1_loop_busy_reg = RegNext(loop_busy_reg)
    
    // === Input pipeline ===
    val order_reg_update = Wire(Bool())
    order_reg_update := false.B
    // Read the next symbol 
    val valid_symbol_req = len_reg =/= 0.U
    io.sp_read.en := valid_symbol_req
    io.sp_read.addr := pipeline_buffer(addr_reg, order_reg_update)
    // Don't update if downstream is blocked
    when (valid_symbol_req && order_reg_update) {
        addr_reg := addr_reg + 1.U
        len_reg := len_reg - 1.U
    }
    // Convert to Huffman tree order
    val valid_order_req = RegInit(false.B)
    when (order_reg_update) { valid_order_req := valid_symbol_req }
    val symbol_resp = pipeline_buffer(io.sp_read.data, order_reg_update)
    io.order_table_port.addr := io.order_table | symbol_resp
    val order_vec_idx = RegNext(symbol_resp(2, 0))
    val order_vec = Wire(Vec(8, UInt(8.W)))
    order_vec := io.order_table_port.data.asTypeOf(order_vec)
    // Update when the flag is raise
    val valid_order_resp = RegInit(false.B)
    when (order_reg_update) { valid_order_resp := valid_order_req }
    when (order_reg_update) {
        order_reg := order_vec(order_vec_idx)
    }

    // === Main loop ===
    // Table request
    // Request for max order table
    when (lut_request_reg) {
        io.table_port(1).addr := io.code_table(1) | Cat(table_idx, 0.U(3.W))
        io.table_port(0).addr := io.code_table(0) | Cat(table_idx, 0.U(3.W))
    }
    // Request for the index of next table
    .otherwise {
        io.table_port(0).addr := io.table_idx_table | Cat(table_idx_reg, match_child)
    }
    
    // Table data process
    when (lut_request_reg) {
        when (!s1_loop_busy_reg) {
            // If we just started the loop, no need to process any return data
            table_idx := 0.U
        } .otherwise {
            // Save the next table index
            val next_table_vec = Wire(Vec(8, UInt(8.W)))
            next_table_vec := io.table_port(0).data.asTypeOf(next_table_vec)
            table_idx := next_table_vec(match_child_reg(2, 0))
            table_idx_reg := table_idx
            // If we reach the end of the block...
            when (table_idx === 0.U) {
                // Adjust the output length code
                buffer.io.len_code := code_len_reg
                // Change the symbol order register
                order_reg_update := true.B
            } .otherwise {
                // Output full length
                buffer.io.len_code := 0.U
            }
            // Output data into the buffer
            buffer.io.in_en := true.B
            buffer.io.shift_in := match_child_reg
        }
    } .otherwise {
        // Feed the symbol comparison logic
        max_symbols := Cat(io.table_port(1).data, io.table_port(0).data)
    }

    val flush_reg = RegInit(false.B)
    when (flush_reg && !buffer.io.out.valid) { flush_reg := false.B }
    buffer.io.flush := flush_reg
    // Loop termination control
    when (loop_busy_reg) {
        // Terminate the loop when we have no more valid symbol entering the loop
        when (order_reg_update && !valid_order_resp) {
            loop_busy_reg := false.B
            flush_reg := true.B
        }
        // Switch the loop state
        lut_request_reg := !lut_request_reg
    } .otherwise {
        // Start the loop when new symbol is coming in
        when (valid_order_resp) {
            loop_busy_reg := true.B
            lut_request_reg := true.B
        }
        // Keep the input pipeline open
        order_reg_update := true.B
    }

    // === Startup control ===
    // To be ready, symbol fetch pipeline must be empty, main loop must be inactive, and no flush is happening
    io.req.ready := len_reg === 0.U && !loop_busy_reg && !flush_reg
    when (io.req.fire() && io.req.bits.length =/= 0.U) {
        addr_reg := io.req.bits.head
        len_reg := io.req.bits.length
        table_idx_reg := 0.U
    }
}

class DecodeShiftBuffer extends Module {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val refill = Flipped(Decoupled(UInt(8.W)))
        val shift = Flipped(Valid(UInt(2.W))) // WARNING: if valid, the value is (shamt - 1).
        val out = Valid(UInt(4.W))
    })

    val buffer_reg = RegInit(0.U(16.W))
    val valid_reg = RegInit(0.U(16.W))

    val shifted_buffer = Mux(io.shift.valid, (buffer_reg << 1) << io.shift.bits, buffer_reg)
    val shifted_valid = Mux(io.shift.valid, (valid_reg << 1) << io.shift.bits, valid_reg)

    val load_high = !shifted_valid(15, 8).orR
    val refill_shamt = Cat(load_high, Fill(3, !load_high) & PriorityEncoder(shifted_valid(15, 8)))
    val refill_valid = Cat(0.U(8.W), Fill(8, io.refill.fire())) << refill_shamt
    val refill_buffer = Cat(0.U(8.W), io.refill.bits) << refill_shamt
    
    io.refill.ready := ~shifted_valid(7, 0).orR
    val next_valid = shifted_valid(15, 0) | refill_valid
    valid_reg := next_valid
    val next_buffer = shifted_buffer(15, 0) | (refill_buffer & Fill(16, io.refill.fire()))
    buffer_reg := next_buffer

    io.out.valid := shifted_valid(15, 12).orR
    io.out.bits := shifted_buffer(15, 12)

    when (io.flush) {
        buffer_reg := 0.U
        valid_reg := 0.U
    }
}

// Huffman tree decoder
class HuffmanDecoder(table_addr_width: Int, sp_addr_width: Int) extends HuffmanModule(table_addr_width, sp_addr_width) {
    val addr_reg = Reg(UInt(32.W))
    val len_reg = RegInit(0.U(32.W))
    
    io.sp_read.en := false.B
    io.sp_read.addr := DontCare
    io.resp.valid := false.B
    io.resp.bits := DontCare
    val buffer = Module(new DecodeShiftBuffer)
    buffer.io.flush := false.B
    buffer.io.shift.valid := false.B
    buffer.io.shift.bits := 0.U
    buffer.io.refill.valid := false.B
    buffer.io.refill.bits := DontCare

    // IO Initialization
    io.table_port(1).addr := 0.U
    io.table_port(0).addr := 0.U

    // Buffer function
    def pipeline_buffer(input: UInt, control: Bool) = {
        val pipe_buffer_reg = Reg(input.cloneType)
        when (control) {
            pipe_buffer_reg := input
        }
        Mux(control, input, pipe_buffer_reg)
    }

    // ==========
    // Loop control registers and wires
    val valid_lut_resp_reg = RegNext(buffer.io.out.valid)

    // Refill pipeline
    val valid_code_req = len_reg =/= 0.U
    io.sp_read.en := valid_code_req
    io.sp_read.addr := pipeline_buffer(addr_reg, buffer.io.refill.ready)
    when (valid_code_req && buffer.io.refill.ready) {
        addr_reg := addr_reg + 1.U
    }
    val valid_code_resp = RegInit(false.B)
    when (buffer.io.refill.ready) { valid_code_resp := valid_code_req }
    val code_resp_reg = RegNext(pipeline_buffer(io.sp_read.data, buffer.io.refill.ready))
    val valid_refill_req = RegInit(false.B)
    when (buffer.io.refill.ready) { valid_refill_req := valid_code_resp }
    buffer.io.refill.valid := valid_refill_req
    buffer.io.refill.bits := code_resp_reg
    
    val s1_buffer_out_reg = RegNext(buffer.io.out.bits)

    val table_idx = Wire(UInt(6.W))
    table_idx := 0.U
    val output_symbol = Wire(UInt(8.W))
    output_symbol := DontCare
    val order_req_valid = Wire(Bool())
    order_req_valid := false.B
    val order_resp_valid = RegNext(order_req_valid)
    val s1_output_symbol_reg = RegNext(output_symbol)

    // === Main loop ===
    // Request
    io.table_port(1).addr := io.table_idx_table | Cat(table_idx, buffer.io.out.bits)
    io.table_port(0).addr := io.code_table(buffer.io.out.bits(3)) | Cat(table_idx, 0.U(3.W))
    io.order_table_port.addr := io.order_table | Cat(output_symbol)

    // Process
    when (valid_lut_resp_reg) {
        // Get the next table to check
        val next_table_vec = Wire(Vec(8, UInt(8.W)))
        next_table_vec := io.table_port(1).data.asTypeOf(next_table_vec)
        table_idx := next_table_vec(s1_buffer_out_reg(2, 0))

        // Calculate max order of each subtree
        val max_order_vec = Wire(Vec(8, UInt(8.W)))
        max_order_vec := io.table_port(0).data.asTypeOf(max_order_vec)

        // Match the order of each subtree with the target subtree that contain the desired symbol
        // If multiple adjacent subtrees have the same maximum order, they are actually the "projection"
        // of a leaves onto the bottom level of this block (remember every block has 4 levels)
        val target_max_order = max_order_vec(s1_buffer_out_reg(2, 0))

        val symbol_shift = PriorityEncoder(
            Cat(
                true.B,
                max_order_vec(Cat(s1_buffer_out_reg(2, 1), 0.U(1.W))) === max_order_vec(Cat(s1_buffer_out_reg(2, 1), 1.U(1.W))),
                max_order_vec(Cat(s1_buffer_out_reg(2), 0.U(2.W))) === max_order_vec(Cat(s1_buffer_out_reg(2), 3.U(2.W))),
                max_order_vec(0) === max_order_vec(7)
            )
        )

        // Update next index
        buffer.io.shift.valid := true.B
        buffer.io.shift.bits := symbol_shift
        // Translate order back to symbol
        output_symbol := target_max_order
        when (table_idx === 0.U) { // Done decoding
            // Update remaining symbol length
            when (len_reg =/= 0.U) {
                order_req_valid := true.B
                len_reg := len_reg - 1.U
            }
        }
    }
    
    // Output pipeline
    when (order_resp_valid) {
        // Get translated symbol
        val symbol_vec = Wire(Vec(8, UInt(8.W)))
        symbol_vec := io.order_table_port.data.asTypeOf(symbol_vec)
        io.resp.valid := true.B
        io.resp.bits := symbol_vec(s1_output_symbol_reg(2, 0))
        // If we are done, clean up and go back to ready
        when (len_reg === 0.U) {
            buffer.io.flush := true.B // This should invalidate data in the buffer immediately
        }
    }

    // === Startup logic ===
    io.req.ready := len_reg === 0.U && !buffer.io.flush
    when (io.req.fire()) {
        addr_reg := io.req.bits.head
        len_reg := io.req.bits.length
    }
}