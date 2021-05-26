module test_mem(
    input clk,
    input [15:0] in,
    output reg [7:0] out
);

    reg [7:0] ram [0:1023];
    always @(posedge clk) begin
        out <= ram[in];
    end

endmodule 

module huffman_tb;
    reg clk = 0;
    reg rst;
    always #(`CLOCK_PERIOD / 2) clk = ~clk;

    // For some reason, the bytes are in reversed order in the memory when loaded by $loadmem
    // Therefore, need to reverse it when writing them into the memory
    function [63:0] reverse_byte;
        input [63:0] in;
        begin
            assign reverse_byte = {in[7:0], in[15:8], in[23:16], in[31:24], in[39:32], in[47:40], in[55:48], in[63:56]};
        end
    endfunction

    integer k;
    integer cycles;
    reg encoding;
    reg pass;

    wire [15:0] sp_read_addr;
    wire [7:0] sp_read_data;
    // Table
    reg [63:0] table [0:319];
    // Input
    wire [7:0] source_out;
    test_mem source(clk, sp_read_addr, source_out);
    // Output
    wire [7:0] compressed_out;
    test_mem compressed(clk, sp_read_addr, compressed_out);
    assign sp_read_data = encoding ? source_out : compressed_out;

    // Address Register
    wire [10:0] csr [0:3];
    assign csr[0] = 0;
    assign csr[1] = 16 * 64;
    assign csr[2] = 16 * 64 + 8 * 64;
    assign csr[3] = 0;

    reg write_en;
    reg [7:0] write_addr;
    reg [63:0] write_data;
    reg write_order_table;
    wire sp_read_en;
    wire req_ready;
    reg req_valid;
    reg [31:0] req_bits_head;
    reg [31:0] req_bits_length;
    wire resp_valid;
    wire [7:0] resp_bits;
    reg csr_write;
    reg [1:0] csr_addr;
    reg [10:0] csr_data;
    Top dut(
        .clock(clk),
        .reset(rst),
        .io_req_ready(req_ready),
        .io_req_valid(req_valid),
        .io_req_bits_head(req_bits_head),
        .io_req_bits_length(req_bits_length),
        .io_sp_read_en(sp_read_en),
        .io_sp_read_addr(sp_read_addr),
        .io_sp_read_data(sp_read_data),
        .io_resp_valid(resp_valid),
        .io_resp_bits(resp_bits),
        .io_encoding(encoding),
        .io_write_en(write_en),
        .io_write_addr(write_addr),
        .io_write_data(write_data),
        .io_write_order_table(write_order_table),
        .io_csr_write(csr_write),
        .io_csr_addr(csr_addr),
        .io_csr_data(csr_data)
    );

    integer fd;

    // Provide stimulus
    initial begin
        cycles = 0;
        #0;
        $vcdpluson;

        rst = 1;
        req_valid = 0;
        cycles = 0;
        pass = 1;
        encoding = 0;
        write_en = 0;
        csr_write = 0;
        write_addr = 0;
        write_data = 0;
        write_order_table = 0;
        req_bits_head = 0;
        req_bits_length = 0;
        csr_addr = 0;
        csr_data = 0;

        // Load arrays
        for (k = 0; k < 1024; k = k + 1) begin
            compressed.ram[k] = 0;
            source.ram[k] = 0;
        end
        fd = $fopen("../build/table.dat", "rb");
        $fread(table, fd);
        $fclose(fd);
        fd = $fopen("../build/ref_data.dat", "rb");
        $fread(compressed.ram, fd);
        $fclose(fd);
        fd = $fopen("../build/sample_data.txt", "rb");
        $fread(source.ram, fd);
        $fclose(fd);

        // Hold reset signal for some time
        repeat (10) @(posedge clk);
        // Note: we should reset on the negedge clk to prevent
        // race behavior (caught by +evalorder in VCS)
        @(negedge clk);
        rst = 0;

        // Load table
        write_en = 1'b1;
        write_order_table = 1'b0;
        for (k = 0; k < 256; k = k + 1) begin
            write_addr = k;
            write_data = reverse_byte(table[k]);
            @(negedge clk);
        end
        write_order_table = 1'b1;
        for (k = 0; k < 64; k = k + 1) begin
            write_addr = k;
            write_data = reverse_byte(table[k + 256]);
            @(negedge clk);
        end
        write_en = 0;
        // Wait for a few cycles
        repeat (10) @(posedge clk);
        @(negedge clk);

        // Load csr
        csr_write = 1;
        for (k = 0; k < 4; k = k + 1) begin
            csr_addr = k;
            csr_data = csr[k];
            @(negedge clk);
        end
        csr_write = 0;

        // Encode test
        $display("Start encoding test...");
        encoding = 1;
        req_bits_head = 0;
        req_bits_length = 1000;
        req_valid = 1;
        @(negedge clk);
        req_valid = 0;
        @(posedge clk);
        
        k = 0;
        while (k < 523) begin
            while (!resp_valid) @(posedge clk);
            if (req_ready) begin
                $display("Encoding failed: early termination");
                pass = 0;
                k = 1000;
            end
            else if (compressed.ram[k] != resp_bits) begin
                $display("Encoding output mismatch at %d: expected %d, got %d", k, compressed.ram[k], resp_bits);
                pass = 0;
            end
            k = k + 1;
            @(posedge clk);
        end
        while (!req_ready) begin
            if (resp_valid) begin
                $display("Encoding failed: more outputs than expected");
                pass = 0;
            end
            @(posedge clk);
        end

        repeat (10) @(posedge clk);

        // Decode test
        @(negedge clk);
        $display("Start decoding test...");
        encoding = 0;
        csr_write = 1;
        csr_addr = 0;
        csr_data = 256;
        repeat (2) @(negedge clk);
        csr_write = 0;
        req_valid = 1;
        @(negedge clk);
        req_valid = 0;
        @(posedge clk);

        k = 0;
        while (k < 1000) begin
            while (!resp_valid) @(posedge clk);
            if (req_ready) begin
                $display("Decoding failed: early termination");
                pass = 0;
                k = 1000;
            end
            else if (source.ram[k] != resp_bits) begin
                $display("Decoding output mismatch at %d: expected %d, got %d", k, source.ram[k], resp_bits);
                pass = 0;
            end
            k = k + 1;
            @(posedge clk);
        end
        while (!req_ready) begin
            if (resp_valid) begin
                $display("Decoding failed: more outputs than expected");
                pass = 0;
            end
            @(negedge clk);
        end

        repeat (10) @(posedge clk);

        // Exit
        if (pass) $display("All tests PASSED!");
        else $display("Tests FAILED.");
        $vcdplusoff;
        $finish;
    end

    // Timeout 
    always @(negedge clk) begin
        if (cycles > 10000) begin
            $display("TIMEOUT - FAILED");
            $vcdplusoff;
            $finish;
        end
        cycles = cycles + 1;
    end
endmodule