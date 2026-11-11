pragma circom 2.2.0;
include "../withdraw.circom";
component main {public [withdrawnValue, stateRoot, stateTreeDepth, ASPRoot, ASPTreeDepth, context]} = Withdraw(32);
