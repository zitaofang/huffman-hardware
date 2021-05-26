# Hardware Implementation of Huffman Coding

This is a repository for "Efficient Hardware Implementation for Huffman Coding". 

This repo is organized as followed:

- `data/`: The test data directory, containing the text file used for our implementation (`sample_data.txt`). To use other input data, replace the file name in `main()` in `software/table_gen_test.cpp`.
- `software/`: The software implementation for generating lookup table and reference Huffman encoded output for testing.
- `src/`: The Chisel source code directory.
    - `main/scala/huffman/Huffman.scala`: The implementation of the Huffman encoder and decoder.
    - `main/scala/huffman/HuffmanTop.scala`: The top module for generation of Verilog code.
    - `test/`: The Scala tests based on Treadle. It is used for performance testing.
- `verilog/`: Additional Verilog code for VLSI workflow.
    - `memcell.v`: The SRAM model refered in the Chisel files. We use the ASAP7 SRAM model provided in [HAMMER](https://github.com/ucb-bar/hammer/tree/master/src/hammer-vlsi/technology/asap7). If you are using a different PDK, replace the underlying SRAM cells in this file.
    - `huffman_tb.v`: The Verilog testbench identical to the Scala tests. We use this to test the post-synthesis and post-P&R functionality.
- `design.yml`: The HAMMER constraints for P&R, including the constraints of the SRAM. This works for ASAP7 PDK only; if you use other technology for P&R, you need to tweak this file accordingly. 

## Build Steps

We haven't preP&Red a Makefile yet. To build the project, follow these step in the root directory:

1. Build the software: 
```
g++ -g -o table_gen software/software_model.c table_gen_test.cpp table_gen.cpp
```
This will generate a executable `table_gen` in the root directory. It will run the unit tests to check if the table generation code is implemented correctly,
and it will generate the lookup table and reference encoded output in `data/`.

2. Generate Verilog:
Run `sbt` in the root directory, and run `runMain huffman.VerilogMain`. This will generate the Verilog source in the root directory. Copy `Top.v` to `verilog/`. 

3. Run HAMMER workflow;
The commands and additional configuration files vary depends on the HAMMER configuration. See [HAMMER Docs](https://hammer-vlsi.readthedocs.io/en/latest/index.html) for more information. Generally, you will need to list `Top.v` and `memcell.v` as the file to synthesis, and use `huffman_tb.v` as your testbench. 
