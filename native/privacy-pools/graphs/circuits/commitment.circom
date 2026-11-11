pragma circom 2.2.0;
include "../commitment.circom";
component main {public [value, label]} = CommitmentHasher();
