#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#include "table_gen.h"
#include "software_model.h"

void test_ht() {
    // A frequency table of 8 symbols. This is a classic example to show how Huffman trees work.
    unsigned weights[8] = { 1, 2, 4, 8, 16, 32, 64, 128 };
    auto root = build_huffman_tree(weights, 8);

    // Reference output for symbol order. Remember that we are producing a canonical tree.
    unsigned char leaf_symbol_ref[] = {7, 6, 5, 4, 3, 2, 0, 1};
    // Traverse the tree. We know the tree structure, so we will use a loop here.
    // The loop goes over all internal node. 
    for (int i = 0; i < 7; i++) {
        // Check the current node.
        assert(root);
        assert(root->symbol == 0);
        assert(root->weight == (1 << (8 - i)) - 1);
        assert(root->num_symbol == 8 - i);
        // Check the left child. In this tree, the left child is always a leaf. 
        assert(root->left);
        assert(root->left->symbol == leaf_symbol_ref[i]);
        assert(root->left->weight == weights[leaf_symbol_ref[i]]);
        assert(root->left->num_symbol == 1);
        assert(!root->left->left);
        assert(!root->left->right);
        // Clean up and move onto the next internal node (on the right).
        auto next_root = root->right;
        delete root->left;
        delete root;
        root = next_root;
    }
    // Now we are at the last node. Check the property.
    assert(root);
    assert(root->symbol == leaf_symbol_ref[7]);
    assert(root->weight == weights[leaf_symbol_ref[7]]);
    assert(root->num_symbol == 1);
    assert(!root->left);
    assert(!root->right);
    // Clean up.
    delete root;
}

void test_generate_table() {
    // Again, the classic frequency table of 8 symbols.
    const unsigned weights[8] = { 1, 2, 4, 8, 16, 32, 64, 128 };

    // Call table generation
    struct table_root_t table_root;
    generate_table(&table_root, weights, 8);

    // Reference output
    // Draw the tree on a piece of paper to understand.
    const unsigned char canon_ref[8] = { 6, 7, 5, 4, 3, 2, 1, 0 };
    const unsigned char canon_decode_ref[8] = { 7, 6, 5, 4, 3, 2, 0, 1 };
    const unsigned char max_table0_ref[16] = { 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 7 };
    const unsigned char max_table1_ref[16] = { 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 7 };
    const unsigned char next_table0_ref[16] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };
    const unsigned char next_table1_ref[16] = { 0 };

    // Calculate second table offset.
    unsigned char *next_table1 = table_root.next_table + 16;
    unsigned char *lower_max_table1 = table_root.lower_max_lut + 8;
    unsigned char *upper_max_table1 = table_root.upper_max_lut + 8;

    // Compare the data. 
    for (int i = 0; i < 8; i++)
        assert(canon_ref[i] == table_root.canonical_lut[i]);
    for (int i = 0; i < 8; i++)
        assert(canon_decode_ref[i] == table_root.canonical_decode_lut[i]);
    for (int i = 0; i < 8; i++)
        assert(max_table0_ref[i] == table_root.lower_max_lut[i]);
    for (int i = 0; i < 8; i++)
        assert(max_table0_ref[i + 8] == table_root.upper_max_lut[i]);
    for (int i = 0; i < 8; i++)
        assert(max_table1_ref[i] == lower_max_table1[i]);
    for (int i = 0; i < 8; i++)
        assert(max_table1_ref[i + 8] == upper_max_table1[i]);
    for (int i = 0; i < 16; i++)
        assert(next_table0_ref[i] == table_root.next_table[i]);
    for (int i = 0; i < 16; i++)
        assert(next_table1_ref[i] == next_table1[i]);

    // Cleanup
    delete [] table_root.canonical_lut;
    delete [] table_root.canonical_decode_lut;
    delete [] table_root.next_table;
    delete [] table_root.upper_max_lut;
    delete [] table_root.lower_max_lut;
}

void test_output_stream() {
    // Intput and output reference
    int bitstream[] = { 0, 1, 0, 1, 1, 1, 1, 0, 0, 1, 0 };
    unsigned char output_ref = 0b11110010;
    unsigned char buffer_ref = 0b01000000;

    // Run stream
    unsigned char bit_buffer = 0;
    short bit_pos = 0;
    unsigned char output_buffer[2];
    unsigned char* output_pos = &output_buffer[1];
    for (int i = 10; i >= 0; i--) {
        output_bitstream(&output_pos, &bit_pos, &bit_buffer, (bool) bitstream[i]);
    }

    // Check output
    assert(output_buffer[1] == output_ref);
    assert(bit_buffer == buffer_ref);
    assert(output_pos == output_buffer);
    assert(bit_pos == 3);
}

void test_huffman_ref(bool align, bool exact_byte) {
    // Input (12 character, ignore the tailing 0)
    const unsigned char* data = (const unsigned char*) "abbccccdddddd";
    size_t length = 12 + (int) align;
    // The expected code is d=0, c=10, b=110, a=1110 (since we have some 0 freq symbols)
    // So the output should be 11101101101010101000000
    const unsigned char output_ref[] = { (unsigned char) 0b11101101, (unsigned char) 0b10101010, (unsigned char) 0b10000000 };
    
    // Generate ref
    unsigned char out[32] = {0};
    size_t code_length = generate_huffman_ref(data, length, out, exact_byte ? 3 : 32);

    // Compare
    for (size_t i = 0; i < 3; i++)
        assert(out[i] == output_ref[i]);   
}

// Read data and count frequency
void read_data(size_t read_max_length, size_t huffman_limit, const char* filename) {
    // Prepare buffer
    void* data = malloc(read_max_length);
    void* output = malloc((huffman_limit + 1));

    // Open file
    FILE* source = fopen(filename, "r");
    size_t length = fread(data, 1, read_max_length, source);
    fclose(source);

    // Run Huffman
    struct table_root_t table;
    size_t n_table;
    size_t read_len = generate_huffman_ref((unsigned char*) data, length, (unsigned char*)output, huffman_limit, &table, &n_table);

    // Write table
    // We are using standard table size here (check generate_table())
    FILE* table_file = fopen("data/table.dat", "wb");
    fwrite(table.next_table, 1, 16 * 64, table_file);
    fwrite(table.upper_max_lut, 1, 8 * 64, table_file);
    fwrite(table.lower_max_lut, 1, 8 * 64, table_file);
    fwrite(table.canonical_lut, 1, 256, table_file);
    fwrite(table.canonical_decode_lut, 1, 256, table_file);
    fclose(table_file);
    
    // Print table for the article
    // FILE* csv = fopen("sym2ord.csv", "w");
    // char* to_be_check = "edlrsautom i";
    // for (int i = 0; i < 12; i++) 
    //     fprintf(csv, "%c, %d\n", to_be_check[i], table.canonical_lut[to_be_check[i]]);
    // fclose(csv);
    // csv = fopen("main_table.csv", "w");
    // for (int i = 0; i < 16; i++) {
    //     int max_table = i < 8 ? table.lower_max_lut[i] : table.upper_max_lut[i - 8];
    //     fprintf(csv, "%d, %d, %d\n", i, max_table, table.next_table[i]);
    // }
    // fclose(csv);

    // Write output stream
    FILE* dst = fopen("data/ref_data.dat", "wb");
    fwrite(output, 1, read_len, dst);
    fclose(dst);

    // Clean up
    free(data);
    free(output);
}

// Print Huffman tree for the thesis
// static int next_node[5] = {0};
// void print_tab(size_t level, FILE* output) {
//     for (int i = 0; i < level; i++) fprintf(output, "\t");
// }

// void print_huffman_tree_imaginary(huffman_t* current, size_t level, FILE* output) {
//     if (level == 5) {
//         fprintf(output, " ");
//         return;
//     }
//     print_tab(level, output);
//     fprintf(output, "[.\\node[imaginary](node_%d_%d){$%c_{%d, %d}$};\n", level, next_node[level], current->symbol, level, next_node[level]);
//     next_node[level]++;
//     print_huffman_tree_imaginary(current, level + 1, output);
//     print_huffman_tree_imaginary(current, level + 1, output);
//     print_tab(level, output);
//     fprintf(output, "]\n");
// }

// void print_huffman_tree_internal(huffman_t* current, size_t level, FILE* output) {
//     print_tab(level, output);
//     if (current->left || current->right) {
//         fprintf(output, "[.\\node(node_%d_%d){%d, %d};\n", level, next_node[level], level, next_node[level]);
//         if (level == 5) {
//             print_tab(level + 1, output);
//             fprintf(output, "[\\edge[dashed]; \\node[blank](end1_%d_%d){}; ]\n", level, next_node[level]);
//             print_tab(level + 1, output);
//             fprintf(output, "[\\edge[dashed]; \\node[blank](end2_%d_%d){}; ]\n", level, next_node[level]);
//         } else {
//             print_huffman_tree_internal(current->left, level + 1, output);
//             print_huffman_tree_internal(current->right, level + 1, output);
//         }
//     } else {
//         fprintf(output, "[.\\node(node_%d_%d){%c}; \n", level, next_node[level], current->symbol);
//         if (level < 4) {
//             print_huffman_tree_imaginary(current, level + 1, output);
//             print_huffman_tree_imaginary(current, level + 1, output);
//         }
//     }
//     print_tab(level, output);
//     fprintf(output, "]\n");
//     if (level == 0) printf("next_node: %d\n", next_node[level]);
//     next_node[level]++;
// }

// void print_huffman_tree(huffman_t* root) {
//     FILE* output = fopen("tikz.txt", "w");
//     for (int i = 0; i < 5; i++) next_node[i] = 0;
//     print_huffman_tree_internal(root, 0, output);
//     fclose(output);
// }

// ========================

int main() {
    test_ht();
    test_generate_table();
    test_output_stream();
    test_huffman_ref(true, true);
    test_huffman_ref(true, false);
    test_huffman_ref(false, true);
    test_huffman_ref(false, false);

    read_data(1024, 4096, "data/sample_data.txt");
    return 0;
}
