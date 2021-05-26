// Software model of the Huffman encoder / decoder.
#include <stdlib.h>
#include <stdio.h>
#include "table_gen.h"

// Bitstream, return true if flushed
void output_bitstream(unsigned char** pos, short* bit_pos, unsigned char* buf, bool bit) {
    // Shift bit in
    *buf = (*buf) >> 1 | (bit << 7);
    // Flush logic
    if (++*bit_pos == 8) {
        *(*pos)-- = *buf;
        *buf = 0;
        *bit_pos = 0;
    }
}

// Generate Huffman tree reference model
size_t generate_huffman_ref(const unsigned char* data, size_t length, unsigned char* output, size_t limit, struct table_root_t* table, 
    size_t* n_table) {
    size_t n_output_bits = 0;
    huffman_t* encode_table[256];
    // Build frequency table
    unsigned freq[256] = {0};
    for (size_t i = 0; i < length; i++) freq[data[i]]++;
    // Generate table
    huffman_t* root = build_huffman_tree(freq, 256, encode_table);
    // DEBUG: Enable this only if printing the tikz output for paper
    print_huffman_tree(root);
    // Encode string
    unsigned char* output_pos = &output[limit - 1];
    unsigned char bit_buffer = 0;
    short bit_pos = 0;
    for (int i = length - 1; i >= 0; i--) {
        // Going up from the leaf node and shift in the huffman code bit (in reverse order)
        // We start from the last symbol and output it to the end of the region
        huffman_t* node = encode_table[data[i]];
        while (node->parent) {
            // Stop if we hit the limit
            if (output_pos < output) {
                fprintf(stderr, "Huffman limit hit");    
                goto break_outer;
            }
            bool bit = node->parent->right == node;
            output_bitstream(&output_pos, &bit_pos, &bit_buffer, bit);
            node = node->parent;
            n_output_bits++;
        }
    }
break_outer:
    // Align the string with the beginning of the output region
    unsigned char* align_pos = output;
    while (++output_pos < &output[limit]) { // We will align the last byte manually
        // Shift the content from the next byte in
        *align_pos++ = bit_buffer | (*((unsigned char*) output_pos) >> bit_pos);
        // Load the byte not used by 
        bit_buffer = *((unsigned char*) output_pos) << (8 - bit_pos);
    }
    *align_pos = bit_buffer;
    // Run table generation
    if (table && n_table)
        *n_table = generate_table(table, freq, 256);
    return align_pos + 1 - output;
}
