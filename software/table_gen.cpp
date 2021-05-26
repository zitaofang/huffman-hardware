#include <queue>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "table_gen.h"

// Build the huffman tree in the normal binary tree format.
huffman_t* build_huffman_tree(const unsigned* freq, short nsymbols
#ifdef STIMULUS_GENERATE
    , huffman_t** encode_table
#endif
) {
    // build Huffman Tree
    auto cmp = [](huffman_t* left, huffman_t* right) { return left->weight > right->weight; };
    std::priority_queue<huffman_t*, std::deque<huffman_t*>, decltype(cmp)> queue(cmp, std::deque<huffman_t*>());
    // Initialize heap with the size
    for (int i = 0; i < nsymbols; i++) {
        huffman_t* node = new huffman_t;
        node->symbol = i;
        node->height = 1;
        node->num_symbol = 1;
        node->weight = freq[i];
        node->left = nullptr;
        node->right = nullptr;
#ifdef STIMULUS_GENERATE
        node->parent = nullptr;
        if (encode_table) encode_table[i] = node;
#endif
        queue.push(node);
    }
    // Merge nodes
    while (queue.size() != 1) {
        huffman_t* node = new huffman_t;
        node->symbol = 0;
        huffman_t* subnode_a = queue.top();
        queue.pop();
        huffman_t* subnode_b = queue.top();
        queue.pop();

        // Determine the order: higher tree to the right, otherwise, smaller symbol value to the left
        // Required for canonical
        bool swap = false;
        if (subnode_a->height > subnode_b->height) swap = true;
        else if ((subnode_a->height == subnode_b->height) && (subnode_a->symbol > subnode_b->symbol)) swap = true;
        if (swap) {
            huffman_t* swap_tmp = subnode_a;
            subnode_a = subnode_b;
            subnode_b = swap_tmp;
        }
        node->left = subnode_a;
        node->right = subnode_b;
        node->height = 1 + (node->left->height > node->right->height ? node->left->height : node->right->height);

        node->num_symbol = node->right->num_symbol + node->left->num_symbol;
        node->weight = node->right->weight + node->left->weight;
#ifdef STIMULUS_GENERATE
        node->parent = NULL;
        node->left->parent = node;
        node->right->parent = node;
#endif
        queue.push(node);
    }
    return queue.top();
}

// Since there are too many recursive arguments, I made a structure to hold them
struct recursive_args_t {
    short code; // The 4-bit code in the current group (remember the Huffman code string is splited every 4 bits)
    short level; // The level since the last string split (< 4)
    unsigned char* next_table; // The location of the current "next table index" table. 
    unsigned char* upper_max_lut; // The location of the upper half of the current "max symbol" table.
    unsigned char* lower_max_lut; // The location of the lower half of the current "max symbol" table.
};

// Global counter for the recursion
struct global_counter_t {
    int current_canon;
    int table_idx;
};

// Table traverser. It is supposed to return the latest canonical code slot.
huffman_t *generate_entry(huffman_t *node, const table_root_t* table_root, const recursive_args_t* args, global_counter_t* counter) {
    // If we reach a leaf node...
    if (!node->left && !node->right) {
        // write current canonical code
        table_root->canonical_lut[node->symbol] = counter->current_canon;
        table_root->canonical_decode_lut[counter->current_canon] = node->symbol;
        // All "virtual leaf" under the current leaf node in the table will be filled in
        // the same content. 
        int n_virtual_leaves = 1 << (3 - args->level);
        // Fill in 0 to the corresponding entries for these leaves in next_table
        memset(&args->next_table[args->code], 0, n_virtual_leaves);
        // Fill in the symbol to the current max_lut
        if (args->code < 8) // The lower 8 entries
            memset(&args->lower_max_lut[args->code], counter->current_canon, n_virtual_leaves);
        else // The upper 8 entries
            memset(&args->upper_max_lut[args->code - 8], counter->current_canon, n_virtual_leaves);
        // Update pointer to the next available canonical code
        counter->current_canon++;
    }
    // If we are at a intermediate node at level 3...
    else if (args->level == 3) {
        // Set next_table to the next available table slot
        args->next_table[args->code] = counter->table_idx;
        // Build new argument structure 
        recursive_args_t next_args;
        next_args.level = 0;
        // Calculate the position of new table
        next_args.next_table = counter->table_idx * 16 + table_root->next_table;
        next_args.upper_max_lut = counter->table_idx * 8 + table_root->upper_max_lut;
        next_args.lower_max_lut = counter->table_idx * 8 + table_root->lower_max_lut;
        // Since we used a slot, increment the next available table pointer
        counter->table_idx++;
        // Call recursively for the left branch
        next_args.code = 0;
        node->left = generate_entry(node->left, table_root, &next_args, counter);
        // After the recursive call on the left child finished, we can now determine 
        // the max symbol in this subtree very easily
        int max_symbol = node->right->num_symbol + counter->current_canon - 1;
        if (args->code < 8) // The lower 8 entries
            args->lower_max_lut[args->code] = max_symbol;
        else // The upper 8 entries
            args->upper_max_lut[args->code - 8] = max_symbol;
        // Finish the right tree
        next_args.code = 8;
        node->right = generate_entry(node->right, table_root, &next_args, counter);
    }
    // Otherwise...
    else {
        // We run recursively on left and right node.
        recursive_args_t next_args = *args;
        next_args.level++;
        node->left = generate_entry(node->left, table_root, &next_args, counter);
        next_args.code += (1 << (2 - args->level));
        node->right = generate_entry(node->right, table_root, &next_args, counter);
    }
    // Before we return, clean up the node
    delete node;
    return NULL;
}

// Read table from the scratchpad and generate requried lookup table.
size_t generate_table(struct table_root_t* result, const unsigned* freq, short nsymbols) {
    if (nsymbols > 256) {
        fprintf(stderr, "generate_table doesn't support nsymbols > 256\n");
        exit(-1);
    }
    auto root = build_huffman_tree(freq, nsymbols);
    
    // Traverse the node and build tables
    // Even if nsymbols < 256, we still create a 256 table for convenience.
    struct table_root_t* table_root = result;
    table_root->canonical_lut = new unsigned char[256];
    table_root->canonical_decode_lut = new unsigned char[256];
    table_root->next_table = new unsigned char[16 * 64];
    table_root->upper_max_lut = new unsigned char[8 * 64];
    table_root->lower_max_lut = new unsigned char[8 * 64];
    // To avoid undefined behavior, initialize all of these to 0. 
    // (Not technically correct for canonical_lut but sufficient)
    memset(table_root->canonical_lut, 0, 256);
    memset(table_root->canonical_decode_lut, 0, 256);
    memset(table_root->next_table, 0, 16 * 64);
    memset(table_root->upper_max_lut, 0, 8 * 64);
    memset(table_root->lower_max_lut, 0, 8 * 64);

    // Prepare global counter
    struct global_counter_t counter;
    counter.current_canon = 0;
    counter.table_idx = 1;
    
    // Prepare top-level recursion arguments
    struct recursive_args_t args;
    args.code = 0;
    args.level = -1;
    args.next_table = table_root->next_table;
    args.upper_max_lut = table_root->upper_max_lut;
    args.lower_max_lut = table_root->lower_max_lut;

    // Call recursion
    generate_entry(root, table_root, &args, &counter);

    // Return number of tables used
    return counter.table_idx + 1;
}

void delete_huffman_tree(huffman_t* root) {
    if (root->left) delete_huffman_tree(root->left);
    if (root->right) delete_huffman_tree(root->right);
    delete root;
}
