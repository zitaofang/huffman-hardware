#ifndef TABLE_GEN_H_
#define TABLE_GEN_H_

#define STIMULUS_GENERATE

// Temporary Huffman tree cell
struct huffman_t {
    // For leaf node, this is the symbol it represents.
    // For internal node, this is the max symbol under this branch. 
    unsigned char symbol;
    short height;
    short num_symbol;
    unsigned int weight;
    huffman_t *left;
    huffman_t *right;
#ifdef STIMULUS_GENERATE
    huffman_t *parent;
#endif
};

// The root of the tables involved. For next_table and max_table, this is the location of table 0.
struct table_root_t {
    unsigned char* canonical_lut;
    unsigned char* canonical_decode_lut;
    unsigned char* next_table;
    unsigned char* upper_max_lut;
    unsigned char* lower_max_lut;
};

huffman_t* build_huffman_tree(const unsigned* freq, short nsymbols
#ifdef STIMULUS_GENERATE
    , huffman_t** encode_table = NULL
#endif
);
size_t generate_table(struct table_root_t* result, const unsigned* freq, short nsymbols);
void delete_huffman_tree(huffman_t* root);
void print_huffman_tree(huffman_t* root);

#endif