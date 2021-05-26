module MemCell (
    input clock,
    input reset,
    input [6:0] addr1,
    input [31:0] in_data1,
    output [31:0] out_data1,
    input wen1,

    input [6:0] addr2,
    output [31:0] out_data2
);

    // RAM
    SRAM2RW128x32 ram(.A1(addr1),.A2(addr2),.CE1(clock),.CE2(clock),.WEB1(~wen1),
        .WEB2(1'b1),.OEB1(1'b0),.OEB2(1'b0),.CSB1(1'b0),.CSB2(1'b0),.I1(in_data1),.I2(32'b0),.O1(out_data1),.O2(out_data2));

endmodule

module MemCellSmall (
    input clock,
    input reset,
    input [6:0] addr,
    input [31:0] in_data,
    output [31:0] out_data,
    input wen
);

    // RAM
    SRAM1RW64x32 ram(.A(addr[5:0]),.CE(clock),.WEB(~wen),.OEB(1'b0),.CSB(1'b0),.I(in_data),.O(out_data));

endmodule