# TASK4: Design UVM testbench

- This task adds a UVM testbench to verify the previously designed RISCV32UI simplified processor.
- The UVM testbench capable of running [riscv-software-src/riscv-tests](https://github.com/riscv-software-src/riscv-tests)

![Processor UVM testbench](images/rv32core_uvm_tb.png)

# The RV32UI core:
- This is a 5-stage pipelined riscv32ui processor using chisel HDL.
- This supports a sub-set of RV32UI instructions. ([Tiny RISC-V ISA](https://www.csl.cornell.edu/courses/ece6745/handouts/ece6745-tinyrv-isa.txt) + few more)
  - ADD, ADDI, MUL, ORI, SLLI
  - LW, SW
  - JAL, JR
  - BNE, BEQ
  - LUI, AUIPC
  - UNIMP

![Processor architecture](images/processor_architecture.png)

## Requirements
- Scala CLI ([How to install](https://www.chisel-lang.org/docs/installation))
- Xilinx Vivado Design Suite (Tested on versions 2022.2, 2020.1)
- riscv-tests ([git repo](https://github.com/riscv-software-src/riscv-tests))
  - Need a modified version without privilege instructions.
  - Follow [this readme](riscv-tests_modified_files/README.md) to patch riscv-tests repo.

## How to compile
- `sbt run` # The generate verilog code (`PipelinedRV32I.v`) from Chisel code resides inside `generated-src` folder

## How to run UVM testbench

- In `src/test/sv/uvm/top/tb_config_pkg.sv`
  - set `RISCV_TESTS_DIR` to `<riscv-test-repository path>/isa`
  - set the necessary tests in `TESTS[]` array.
- In `src/test/sv/uvm/build.tcl`
  - Set the `UVM_VERBOSITY` to required level (`UVM_NONE`, `UVM_LOW`, `UVM_MEDIUM`, `UVM_HIGH`, `UVM_FULL`, `UVM_DEBUG`)
    - The higher the verbosity value, the more prints will be visible.
- Run vivado and in vivado TCL Console 
```
cd src/test/sv/uvm/
source ./build.tcl
```v