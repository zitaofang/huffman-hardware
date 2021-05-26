#ifndef SOFTWARE_MODEL_H_
#define SOFTWARE_MODEL_H_

void output_bitstream(unsigned char** pos, short* bit_pos, unsigned char* buf, bool bit);
size_t generate_huffman_ref(const unsigned char* data, size_t length, unsigned char* output, size_t limit, struct table_root_t* table = NULL, 
    size_t* n_table = NULL);

#endif