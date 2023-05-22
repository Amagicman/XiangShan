///***************************************************************************************
//  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
//  * Copyright (c) 2020-2021 Peng Cheng Laboratory
//  *
//  * XiangShan is licensed under Mulan PSL v2.
//  * You can use this software according to the terms and conditions of the Mulan PSL v2.
//  * You may obtain a copy of Mulan PSL v2 at:
//  *          http://license.coscl.org.cn/MulanPSL2
//  *
//  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
//  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
//  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
//  *
//  * See the Mulan PSL v2 for more details.
//  ***************************************************************************************/
//
//package xiangshan.backend.decode
//
//import chipsalliance.rocketchip.config.Parameters
//import chisel3._
//import chisel3.util._
//import freechips.rocketchip.rocket.Instructions
//import freechips.rocketchip.util.uintToBitPat
//import utils._
//import utility._
//import xiangshan.ExceptionNO.illegalInstr
//import xiangshan._
//import xiangshan.backend.fu.fpu.FPU
//import freechips.rocketchip.rocket.Instructions._
//import xiangshan.backend.Bundles.{DecodedInst, StaticInst}
//import xiangshan.backend.decode.isa.bitfield.{Riscv32BitInst, RiscvInst, XSInstBitFields}
//import xiangshan.backend.fu.FuType
//import xiangshan.backend.fu.vector.Bundles.VType
//import yunsuan.VpermType
//
//trait VectorConstants {
//  val MAX_VLMUL = 8
//  val INT_VCONFIG = 32
//  val FP_TMP_REG_MV = 32
//  val VECTOR_TMP_REG_LMUL = 32 // 32~46  ->  15
//  val VCONFIG_IDX = 39
//}
//
//class DecodeUnitComplexIO(implicit p: Parameters) extends XSBundle {
//  val enq = new Bundle {
//    val staticInst = Input(new StaticInst)
//  }
//  val vtype = Input(new VType)
//  val isComplex = Input(Vec(DecodeWidth - 1, Bool()))
//  val validFromIBuf = Input(Vec(DecodeWidth, Bool()))
//  val readyFromRename = Input(Vec(RenameWidth, Bool()))
//  val deq = new Bundle {
//    val decodedInsts = Output(Vec(RenameWidth, new DecodedInst))
//    val isVset = Output(Bool())
//    val readyToIBuf = Output(Vec(DecodeWidth, Bool()))
//    val validToRename = Output(Vec(RenameWidth, Bool()))
//    val complexNum = Output(UInt(3.W))
//  }
//  val csrCtrl = Input(new CustomCSRCtrlIO)
//}
//
//class DecodeUnitComplex(private val maxNumOfUop: Int)(implicit p : Parameters) extends XSModule with DecodeUnitConstants with VectorConstants {
//  val io = IO(new DecodeUnitComplexIO)
//  //input bits
//  val staticInst = Wire(new StaticInst)
//
//  staticInst := io.enq.staticInst
//
//  private val instr: XSInstBitFields = staticInst.instr.asTypeOf(new XSInstBitFields)
//
//  val src1 = Cat(0.U(1.W), instr.RS1)
//  val src2 = Cat(0.U(1.W), instr.RS2)
//  val dest = Cat(0.U(1.W), instr.RD )
//
//  //output bits
//  val decodedInsts = Wire(Vec(RenameWidth, new DecodedInst))
//  val validToRename = Wire(Vec(RenameWidth, Bool()))
//  val readyToIBuf = Wire(Vec(DecodeWidth, Bool()))
//  val complexNum = Wire(UInt(3.W))
//
//  //output of DecodeUnit
//  val decodedInstSimple = Wire(new DecodedInst)
//  val isVsetSimple = Wire(Bool())
//
//  //pre decode
//  val simple = Module(new DecodeUnit)
//  simple.io.enq.ctrlFlow := staticInst
//  simple.io.enq.vtype := io.vtype
//  simple.io.csrCtrl := io.csrCtrl
//  decodedInstSimple := simple.io.deq.decodedInst
//  isVsetSimple := simple.io.deq.decodedInst.isVset
//
//  when(isVsetSimple) {
//    when(dest === 0.U && src1 === 0.U) {
//      decodedInstSimple.fuOpType := VSETOpType.keepVl(simple.io.deq.decodedInst.fuOpType)
//    }.elsewhen(src1 === 0.U) {
//      decodedInstSimple.fuOpType := VSETOpType.setVlmax(simple.io.deq.decodedInst.fuOpType)
//    }
//    when(io.vtype.illegal){
//      decodedInstSimple.flushPipe := true.B
//    }
//  }
//
//  //Type of uop Div
//  val typeOfSplit = decodedInstSimple.uopSplitType
//
//  //LMUL
//  val lmul = MuxLookup(simple.io.enq.vtype.vlmul, 1.U(4.W), Array(
//    "b001".U -> 2.U,
//    "b010".U -> 4.U,
//    "b011".U -> 8.U
//  ))
//  val numOfUopVslide = MuxLookup(simple.io.enq.vtype.vlmul, 1.U(log2Up(maxNumOfUop+1).W), Array(
//    "b001".U -> 3.U,
//    "b010".U -> 10.U,
//    "b011".U -> 36.U
//  ))
//  val numOfUopVrgather = MuxLookup(simple.io.enq.vtype.vlmul, 1.U(log2Up(maxNumOfUop+1).W), Array(
//    "b001".U -> 4.U,
//    "b010".U -> 16.U,
//    "b011".U -> 64.U
//  ))
//  val numOfUopVrgatherei16 = Mux((!simple.io.enq.vtype.vsew.orR) && (simple.io.enq.vtype.vlmul =/= "b011".U),
//    Cat(numOfUopVrgather, 0.U(1.W)),
//    numOfUopVrgather
//  )
//  val numOfUopVcompress =  MuxLookup(simple.io.enq.vtype.vlmul, 1.U(4.W), Array(
//    "b001".U -> 4.U,
//    "b010".U -> 13.U,
//    "b011".U -> 43.U
//  ))
//  val map: Seq[(Bool, UInt)] =       Seq(
//    (dest =/= 0.U)                                  -> 2.U,
//    (src1 =/= 0.U)                                  -> 1.U,
//    VSETOpType.isVsetvl(decodedInstSimple.fuOpType) -> 2.U,
//  )
//
//  //number of uop
//  val numOfUop = MuxLookup(typeOfSplit, 1.U(log2Up(maxNumOfUop+1).W), Array(
//    UopSplitType.VEC_0XV         -> 2.U,
//    UopSplitType.DIR             -> MuxCase(1.U, Seq(
//      (dest =/= 0.U)                                  -> 2.U,
//      (src1 =/= 0.U)                                  -> 1.U,
//      VSETOpType.isVsetvl(decodedInstSimple.fuOpType) -> 2.U,
//    )),
//    UopSplitType.VEC_VVV         -> lmul,
//    UopSplitType.VEC_EXT2        -> lmul,
//    UopSplitType.VEC_EXT4        -> lmul,
//    UopSplitType.VEC_EXT8        -> lmul,
//    UopSplitType.VEC_VVM         -> lmul,
//    UopSplitType.VEC_VXM         -> (lmul +& 1.U),
//    UopSplitType.VEC_VXV         -> (lmul +& 1.U),
//    UopSplitType.VEC_VVW         -> Cat(lmul, 0.U(1.W)),     // lmul <= 4
//    UopSplitType.VEC_WVW         -> Cat(lmul, 0.U(1.W)),     // lmul <= 4
//    UopSplitType.VEC_VXW         -> Cat(lmul, 1.U(1.W)),     // lmul <= 4
//    UopSplitType.VEC_WXW         -> Cat(lmul, 1.U(1.W)),     // lmul <= 4
//    UopSplitType.VEC_WVV         -> Cat(lmul, 0.U(1.W)),     // lmul <= 4
//    UopSplitType.VEC_WXV         -> Cat(lmul, 1.U(1.W)),     // lmul <= 4
//    UopSplitType.VEC_SLIDE1UP    -> (lmul +& 1.U),
//    UopSplitType.VEC_FSLIDE1UP   -> lmul,
//    UopSplitType.VEC_SLIDE1DOWN  -> Cat(lmul, 0.U(1.W)),
//    UopSplitType.VEC_FSLIDE1DOWN -> (Cat(lmul, 0.U(1.W)) -1.U),
//    UopSplitType.VEC_VRED        -> lmul,
//    UopSplitType.VEC_SLIDEUP     -> (numOfUopVslide + 1.U),
//    UopSplitType.VEC_ISLIDEUP    -> numOfUopVslide,
//    UopSplitType.VEC_SLIDEDOWN   -> (numOfUopVslide + 1.U),
//    UopSplitType.VEC_ISLIDEDOWN  -> numOfUopVslide,
//    UopSplitType.VEC_M0X         -> (lmul +& 1.U),
//    UopSplitType.VEC_MVV         -> (Cat(lmul, 0.U(1.W)) -1.U),
//    UopSplitType.VEC_M0X_VFIRST  -> 2.U,
//    UopSplitType.VEC_VWW         -> Cat(lmul, 0.U(1.W)),
//    UopSplitType.VEC_RGATHER     -> numOfUopVrgather,
//    UopSplitType.VEC_RGATHER_VX  -> (numOfUopVrgather +& 1.U),
//    UopSplitType.VEC_RGATHEREI16 -> numOfUopVrgatherei16,
//  ))
//
//  //uop div up to maxNumOfUop
//  val csBundle = Wire(Vec(maxNumOfUop, new DecodedInst))
//  csBundle.foreach { dst =>
//    dst := decodedInstSimple
//    dst.firstUop := false.B
//    dst.lastUop := false.B
//  }
//
//  csBundle(0).firstUop := true.B
//  csBundle(numOfUop - 1.U).lastUop := true.B
//
//  switch(typeOfSplit) {
//    is(UopSplitType.DIR) {
//      when(dest =/= 0.U) {
//        csBundle(0).fuType := FuType.vsetiwi.U
//        csBundle(0).fuOpType := VSETOpType.switchDest(decodedInstSimple.fuOpType)
//        csBundle(0).flushPipe := false.B
//        csBundle(0).rfWen := true.B
//        csBundle(0).vecWen := false.B
//        csBundle(1).ldest := VCONFIG_IDX.U
//      }.elsewhen(src1 =/= 0.U) {
//        csBundle(0).ldest := VCONFIG_IDX.U
//      }.elsewhen(VSETOpType.isVsetvli(decodedInstSimple.fuOpType)) {
//        csBundle(0).fuType := FuType.vsetfwf.U
//        csBundle(0).srcType(0) := SrcType.vp
//        csBundle(0).lsrc(0) := VCONFIG_IDX.U
//      }.elsewhen(VSETOpType.isVsetvl(decodedInstSimple.fuOpType)) {
//        csBundle(0).srcType(0) := SrcType.reg
//        csBundle(0).srcType(1) := SrcType.imm
//        csBundle(0).lsrc(1) := 0.U
//        csBundle(0).ldest := FP_TMP_REG_MV.U
//        csBundle(0).fuType := FuType.i2f.U
//        csBundle(0).rfWen := false.B
//        csBundle(0).fpWen := true.B
//        csBundle(0).vecWen := false.B
//        csBundle(0).fpu.isAddSub := false.B
//        csBundle(0).fpu.typeTagIn := FPU.D
//        csBundle(0).fpu.typeTagOut := FPU.D
//        csBundle(0).fpu.fromInt := true.B
//        csBundle(0).fpu.wflags := false.B
//        csBundle(0).fpu.fpWen := true.B
//        csBundle(0).fpu.div := false.B
//        csBundle(0).fpu.sqrt := false.B
//        csBundle(0).fpu.fcvt := false.B
//        csBundle(0).flushPipe := false.B
//        csBundle(1).fuType := FuType.vsetfwf.U
//        csBundle(1).srcType(0) := SrcType.vp
//        csBundle(1).lsrc(0) := VCONFIG_IDX.U
//        csBundle(1).srcType(1) := SrcType.fp
//        csBundle(1).lsrc(1) := FP_TMP_REG_MV.U
//        csBundle(1).ldest := VCONFIG_IDX.U
//      }
//    }
//    is(UopSplitType.VEC_VVV) {
//      for (i <- 0 until MAX_VLMUL) {
//        csBundle(i).lsrc(0) := src1 + i.U
//        csBundle(i).lsrc(1) := src2 + i.U
//        csBundle(i).lsrc(2) := dest + i.U
//        csBundle(i).ldest := dest + i.U
//        csBundle(i).uopIdx := i.U
//      }
//    }
//    is(UopSplitType.VEC_EXT2) {
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i).lsrc(1) := src2 + i.U
//        csBundle(2 * i).lsrc(2) := dest + (2 * i).U
//        csBundle(2 * i).ldest := dest + (2 * i).U
//        csBundle(2 * i).uopIdx := (2 * i).U
//        csBundle(2 * i + 1).lsrc(1) := src2 + i.U
//        csBundle(2 * i + 1).lsrc(2) := dest + (2 * i + 1).U
//        csBundle(2 * i + 1).ldest := dest + (2 * i + 1).U
//        csBundle(2 * i + 1).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_EXT4) {
//      for (i <- 0 until MAX_VLMUL / 4) {
//        csBundle(4 * i).lsrc(1) := src2 + i.U
//        csBundle(4 * i).lsrc(2) := dest + (4 * i).U
//        csBundle(4 * i).ldest := dest + (4 * i).U
//        csBundle(4 * i).uopIdx := (4 * i).U
//        csBundle(4 * i + 1).lsrc(1) := src2 + i.U
//        csBundle(4 * i + 1).lsrc(2) := dest + (4 * i + 1).U
//        csBundle(4 * i + 1).ldest := dest + (4 * i + 1).U
//        csBundle(4 * i + 1).uopIdx := (4 * i + 1).U
//        csBundle(4 * i + 2).lsrc(1) := src2 + i.U
//        csBundle(4 * i + 2).lsrc(2) := dest + (4 * i + 2).U
//        csBundle(4 * i + 2).ldest := dest + (4 * i + 2).U
//        csBundle(4 * i + 2).uopIdx := (4 * i + 2).U
//        csBundle(4 * i + 3).lsrc(1) := src2 + i.U
//        csBundle(4 * i + 3).lsrc(2) := dest + (4 * i + 3).U
//        csBundle(4 * i + 3).ldest := dest + (4 * i + 3).U
//        csBundle(4 * i + 3).uopIdx := (4 * i + 3).U
//      }
//    }
//    is(UopSplitType.VEC_EXT8) {
//      for (i <- 0 until MAX_VLMUL) {
//        csBundle(i).lsrc(1) := src2
//        csBundle(i).lsrc(2) := dest + i.U
//        csBundle(i).ldest := dest + i.U
//        csBundle(i).uopIdx := i.U
//      }
//    }
//    is(UopSplitType.VEC_0XV) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      /*
//      vfmv.s.f
//       */
//      csBundle(1).srcType(0) := SrcType.fp
//      csBundle(1).srcType(1) := SrcType.vp
//      csBundle(1).srcType(2) := SrcType.vp
//      csBundle(1).lsrc(0) := FP_TMP_REG_MV.U
//      csBundle(1).lsrc(1) := 0.U
//      csBundle(1).lsrc(2) := dest
//      csBundle(1).ldest := dest
//      csBundle(1).fuType := FuType.vppu.U
//      csBundle(1).fuOpType := VpermType.dummy // Todo
//      csBundle(1).rfWen := false.B
//      csBundle(1).fpWen := false.B
//      csBundle(1).vecWen := true.B
//    }
//    is(UopSplitType.VEC_VXV) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      /*
//      LMUL
//       */
//      for (i <- 0 until MAX_VLMUL) {
//        csBundle(i + 1).srcType(0) := SrcType.fp
//        csBundle(i + 1).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(i + 1).lsrc(1) := src2 + i.U
//        csBundle(i + 1).lsrc(2) := dest + i.U
//        csBundle(i + 1).ldest := dest + i.U
//        csBundle(i + 1).uopIdx := i.U
//      }
//    }
//    is(UopSplitType.VEC_VVW) {
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i).lsrc(0) := src1 + i.U
//        csBundle(2 * i).lsrc(1) := src2 + i.U
//        csBundle(2 * i).lsrc(2) := dest + (2 * i).U
//        csBundle(2 * i).ldest := dest + (2 * i).U
//        csBundle(2 * i).uopIdx := (2 * i).U
//        csBundle(2 * i + 1).lsrc(0) := src1 + i.U
//        csBundle(2 * i + 1).lsrc(1) := src2 + i.U
//        csBundle(2 * i + 1).lsrc(2) := dest + (2 * i + 1).U
//        csBundle(2 * i + 1).ldest := dest + (2 * i + 1).U
//        csBundle(2 * i + 1).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_WVW) {
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i).lsrc(0) := src1 + i.U
//        csBundle(2 * i).lsrc(1) := src2 + (2 * i).U
//        csBundle(2 * i).lsrc(2) := dest + (2 * i).U
//        csBundle(2 * i).ldest := dest + (2 * i).U
//        csBundle(2 * i).uopIdx := (2 * i).U
//        csBundle(2 * i + 1).lsrc(0) := src1 + i.U
//        csBundle(2 * i + 1).lsrc(1) := src2 + (2 * i + 1).U
//        csBundle(2 * i + 1).lsrc(2) := dest + (2 * i + 1).U
//        csBundle(2 * i + 1).ldest := dest + (2 * i + 1).U
//        csBundle(2 * i + 1).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_VXW) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i + 1).srcType(0) := SrcType.fp
//        csBundle(2 * i + 1).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(2 * i + 1).lsrc(1) := src2 + i.U
//        csBundle(2 * i + 1).lsrc(2) := dest + (2 * i).U
//        csBundle(2 * i + 1).ldest := dest + (2 * i).U
//        csBundle(2 * i + 1).uopIdx := (2 * i).U
//        csBundle(2 * i + 2).srcType(0) := SrcType.fp
//        csBundle(2 * i + 2).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(2 * i + 2).lsrc(1) := src2 + i.U
//        csBundle(2 * i + 2).lsrc(2) := dest + (2 * i + 1).U
//        csBundle(2 * i + 2).ldest := dest + (2 * i + 1).U
//        csBundle(2 * i + 2).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_WXW) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i + 1).srcType(0) := SrcType.fp
//        csBundle(2 * i + 1).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(2 * i + 1).lsrc(1) := src2 + (2 * i).U
//        csBundle(2 * i + 1).lsrc(2) := dest + (2 * i).U
//        csBundle(2 * i + 1).ldest := dest + (2 * i).U
//        csBundle(2 * i + 1).uopIdx := (2 * i).U
//        csBundle(2 * i + 2).srcType(0) := SrcType.fp
//        csBundle(2 * i + 2).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(2 * i + 2).lsrc(1) := src2 + (2 * i + 1).U
//        csBundle(2 * i + 2).lsrc(2) := dest + (2 * i + 1).U
//        csBundle(2 * i + 2).ldest := dest + (2 * i + 1).U
//        csBundle(2 * i + 2).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_WVV) {
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i).lsrc(0) := src1 + i.U
//        csBundle(2 * i).lsrc(1) := src2 + (2 * i).U
//        csBundle(2 * i).lsrc(2) := dest + i.U
//        csBundle(2 * i).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i).uopIdx := (2 * i).U
//        csBundle(2 * i + 1).lsrc(0) := src1 + i.U
//        csBundle(2 * i + 1).lsrc(1) := src2 + (2 * i + 1).U
//        csBundle(2 * i + 1).lsrc(2) := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i + 1).ldest := dest + i.U
//        csBundle(2 * i + 1).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_WXV) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//
//      for (i <- 0 until MAX_VLMUL / 2) {
//        csBundle(2 * i + 1).srcType(0) := SrcType.fp
//        csBundle(2 * i + 1).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(2 * i + 1).lsrc(1) := src2 + (2 * i).U
//        csBundle(2 * i + 1).lsrc(2) := dest + i.U
//        csBundle(2 * i + 1).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i + 1).uopIdx := (2 * i).U
//        csBundle(2 * i + 2).srcType(0) := SrcType.fp
//        csBundle(2 * i + 2).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(2 * i + 2).lsrc(1) := src2 + (2 * i + 1).U
//        csBundle(2 * i + 2).lsrc(2) := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i + 2).ldest := dest + i.U
//        csBundle(2 * i + 2).uopIdx := (2 * i + 1).U
//      }
//    }
//    is(UopSplitType.VEC_VVM) {
//      csBundle(0).lsrc(2) := dest
//      csBundle(0).ldest := VECTOR_TMP_REG_LMUL.U
//      csBundle(0).uopIdx := 0.U
//      for(i <- 1 until MAX_VLMUL) {
//        csBundle(i).lsrc(0) := src1 + i.U
//        csBundle(i).lsrc(1) := src2 + i.U
//        csBundle(i).lsrc(2) := VECTOR_TMP_REG_LMUL.U
//        csBundle(i).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(i).uopIdx := i.U
//      }
//      csBundle(numOfUop - 1.U).ldest := dest
//    }
//    is(UopSplitType.VEC_VXM) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      //LMUL
//      csBundle(1).srcType(0) := SrcType.fp
//      csBundle(1).lsrc(0) := FP_TMP_REG_MV.U
//      csBundle(1).lsrc(2) := dest
//      csBundle(1).ldest := VECTOR_TMP_REG_LMUL.U
//      csBundle(1).uopIdx := 0.U
//      for (i <- 1 until MAX_VLMUL) {
//        csBundle(i + 1).srcType(0) := SrcType.fp
//        csBundle(i + 1).lsrc(0) := FP_TMP_REG_MV.U
//        csBundle(i + 1).lsrc(1) := src2 + i.U
//        csBundle(i + 1).lsrc(2) := VECTOR_TMP_REG_LMUL.U
//        csBundle(i + 1).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(i + 1).uopIdx := i.U
//      }
//      csBundle(numOfUop - 1.U).ldest := dest
//    }
//    is(UopSplitType.VEC_SLIDE1UP) {
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      //LMUL
//      csBundle(1).srcType(0) := SrcType.fp
//      csBundle(1).lsrc(0) := FP_TMP_REG_MV.U
//      csBundle(1).lsrc(2) := dest
//      csBundle(1).ldest := dest
//      csBundle(1).uopIdx := 0.U
//      for (i <- 1 until MAX_VLMUL) {
//        csBundle(i + 1).srcType(0) := SrcType.vp
//        csBundle(i + 1).lsrc(0) := src2 + (i - 1).U
//        csBundle(i + 1).lsrc(1) := src2 + i.U
//        csBundle(i + 1).lsrc(2) := dest + i.U
//        csBundle(i + 1).ldest := dest + i.U
//        csBundle(i + 1).uopIdx := i.U
//      }
//    }
//    is(UopSplitType.VEC_FSLIDE1UP) {
//      //LMUL
//      csBundle(0).srcType(0) := SrcType.fp
//      csBundle(0).lsrc(0) := src1
//      csBundle(0).lsrc(1) := src2
//      csBundle(0).lsrc(2) := dest
//      csBundle(0).ldest := dest
//      csBundle(0).uopIdx := 0.U
//      for (i <- 1 until MAX_VLMUL) {
//        csBundle(i).srcType(0) := SrcType.vp
//        csBundle(i).lsrc(0) := src2 + (i - 1).U
//        csBundle(i).lsrc(1) := src2 + i.U
//        csBundle(i).lsrc(2) := dest + i.U
//        csBundle(i).ldest := dest + i.U
//        csBundle(i).uopIdx := i.U
//      }
//    }
//    is(UopSplitType.VEC_SLIDE1DOWN) { // lmul+lmul = 16
//      /*
//      FMV.D.X
//       */
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      //LMUL
//      for (i <- 0 until MAX_VLMUL) {
//        csBundle(2 * i + 1).srcType(0) := SrcType.vp
//        csBundle(2 * i + 1).srcType(1) := SrcType.vp
//        csBundle(2 * i + 1).lsrc(0) := src2 + (i+1).U
//        csBundle(2 * i + 1).lsrc(1) := src2 + i.U
//        csBundle(2 * i + 1).lsrc(2) := dest + i.U
//        csBundle(2 * i + 1).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i + 1).uopIdx := (2 * i).U
//        if (2 * i + 2 < MAX_VLMUL * 2 ){
//          csBundle(2 * i + 2).srcType(0) := SrcType.fp
//          csBundle(2 * i + 2).lsrc(0) := FP_TMP_REG_MV.U
//          // csBundle(2 * i + 2).lsrc(1) := src2 + i.U         // DontCare
//          csBundle(2 * i + 2).lsrc(2) := VECTOR_TMP_REG_LMUL.U
//          csBundle(2 * i + 2).ldest := dest + i.U
//          csBundle(2 * i + 2).uopIdx := (2 * i + 1).U
//        }
//      }
//      csBundle(numOfUop - 1.U).srcType(0) := SrcType.fp
//      csBundle(numOfUop - 1.U).lsrc(0) := FP_TMP_REG_MV.U
//      csBundle(numOfUop - 1.U).ldest := dest + lmul - 1.U
//    }
//    is(UopSplitType.VEC_FSLIDE1DOWN) {
//      //LMUL
//      for (i <- 0 until MAX_VLMUL) {
//        csBundle(2 * i).srcType(0) := SrcType.vp
//        csBundle(2 * i).srcType(1) := SrcType.vp
//        csBundle(2 * i).lsrc(0) := src2 + (i+1).U
//        csBundle(2 * i).lsrc(1) := src2 + i.U
//        csBundle(2 * i).lsrc(2) := dest + i.U
//        csBundle(2 * i).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i).uopIdx := (2 * i).U
//        csBundle(2 * i + 1).srcType(0) := SrcType.fp
//        csBundle(2 * i + 1).lsrc(0) := src1
//        csBundle(2 * i + 1).lsrc(2) := VECTOR_TMP_REG_LMUL.U
//        csBundle(2 * i + 1).ldest := dest + i.U
//        csBundle(2 * i + 1).uopIdx := (2 * i + 1).U
//      }
//      csBundle(numOfUop - 1.U).srcType(0) := SrcType.fp
//      csBundle(numOfUop - 1.U).lsrc(0) := src1
//      csBundle(numOfUop - 1.U).ldest := dest + lmul - 1.U
//    }
//    is(UopSplitType.VEC_VRED) {
//      when(simple.io.enq.vtype.vlmul === "b001".U){
//        csBundle(0).srcType(2) := SrcType.DC
//        csBundle(0).lsrc(0) := src2 + 1.U
//        csBundle(0).lsrc(1) := src2
//        csBundle(0).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(0).uopIdx := 0.U
//      }
//      when(simple.io.enq.vtype.vlmul === "b010".U) {
//        csBundle(0).srcType(2) := SrcType.DC
//        csBundle(0).lsrc(0) := src2 + 1.U
//        csBundle(0).lsrc(1) := src2
//        csBundle(0).ldest := VECTOR_TMP_REG_LMUL.U
//        csBundle(0).uopIdx := 0.U
//
//        csBundle(1).srcType(2) := SrcType.DC
//        csBundle(1).lsrc(0) := src2 + 3.U
//        csBundle(1).lsrc(1) := src2 + 2.U
//        csBundle(1).ldest := (VECTOR_TMP_REG_LMUL+1).U
//        csBundle(1).uopIdx := 1.U
//
//        csBundle(2).srcType(2) := SrcType.DC
//        csBundle(2).lsrc(0) := (VECTOR_TMP_REG_LMUL+1).U
//        csBundle(2).lsrc(1) := VECTOR_TMP_REG_LMUL.U
//        csBundle(2).ldest := (VECTOR_TMP_REG_LMUL+2).U
//        csBundle(2).uopIdx := 2.U
//      }
//      when(simple.io.enq.vtype.vlmul === "b011".U) {
//        for(i <- 0 until MAX_VLMUL){
//          if(i < MAX_VLMUL - MAX_VLMUL/2){
//            csBundle(i).lsrc(0) := src2 + (i * 2 + 1).U
//            csBundle(i).lsrc(1) := src2 + (i * 2).U
//            csBundle(i).ldest := (VECTOR_TMP_REG_LMUL + i).U
//          } else if (i < MAX_VLMUL - MAX_VLMUL/4) {
//            csBundle(i).lsrc(0) := (VECTOR_TMP_REG_LMUL + (i - MAX_VLMUL/2)*2 + 1).U
//            csBundle(i).lsrc(1) := (VECTOR_TMP_REG_LMUL + (i - MAX_VLMUL/2)*2).U
//            csBundle(i).ldest := (VECTOR_TMP_REG_LMUL + i).U
//          }else if (i < MAX_VLMUL - MAX_VLMUL/8) {
//            csBundle(6).lsrc(0) := (VECTOR_TMP_REG_LMUL + 5).U
//            csBundle(6).lsrc(1) := (VECTOR_TMP_REG_LMUL + 4).U
//            csBundle(6).ldest := (VECTOR_TMP_REG_LMUL + 6).U
//          }
//          csBundle(i).srcType(2) := SrcType.DC
//          csBundle(i).uopIdx := i.U
//        }
//      }
//      when (simple.io.enq.vtype.vlmul.orR){
//        csBundle(numOfUop - 1.U).srcType(2) := SrcType.vp
//        csBundle(numOfUop - 1.U).lsrc(0) := src1
//        csBundle(numOfUop - 1.U).lsrc(1) := VECTOR_TMP_REG_LMUL.U + numOfUop - 2.U
//        csBundle(numOfUop - 1.U).lsrc(2) := dest
//        csBundle(numOfUop - 1.U).ldest := dest
//        csBundle(numOfUop - 1.U).uopIdx := numOfUop - 1.U
//      }
//    }
//
//    is(UopSplitType.VEC_SLIDEUP) {
//      // FMV.D.X
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      // LMUL
//      for(i <- 0 until MAX_VLMUL)
//        for(j <- 0 to i){
//          val old_vd = if (j==0) {dest + i.U} else (VECTOR_TMP_REG_LMUL+j-1).U
//          val vd = if (j==i) {dest + i.U} else (VECTOR_TMP_REG_LMUL+j).U
//          csBundle(i*(i+1)/2+j+1).srcType(0) := SrcType.fp
//          csBundle(i*(i+1)/2+j+1).lsrc(0) := FP_TMP_REG_MV.U
//          csBundle(i*(i+1)/2+j+1).lsrc(1) := src2 + j.U
//          csBundle(i*(i+1)/2+j+1).lsrc(2) := old_vd
//          csBundle(i*(i+1)/2+j+1).ldest := vd
//          csBundle(i*(i+1)/2+j+1).uopIdx := (i*(i+1)/2+j).U
//        }
//    }
//
//    is(UopSplitType.VEC_ISLIDEUP) {
//      // LMUL
//      for(i <- 0 until MAX_VLMUL)
//        for(j <- 0 to i){
//          val old_vd = if (j==0) {dest + i.U} else (VECTOR_TMP_REG_LMUL+j-1).U
//          val vd = if (j==i) {dest + i.U} else (VECTOR_TMP_REG_LMUL+j).U
//          csBundle(i*(i+1)/2+j).lsrc(1) := src2 + j.U
//          csBundle(i*(i+1)/2+j).lsrc(2) := old_vd
//          csBundle(i*(i+1)/2+j).ldest := vd
//          csBundle(i*(i+1)/2+j).uopIdx := (i*(i+1)/2+j).U
//        }
//    }
//
//    is(UopSplitType.VEC_SLIDEDOWN) {
//      // FMV.D.X
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//      // LMUL
//      for(i <- 0 until MAX_VLMUL)
//        for(j <- (0 to i).reverse){
//          when(i.U < lmul){
//            val old_vd = if (j==0) {dest + lmul -1.U - i.U} else (VECTOR_TMP_REG_LMUL+j-1).U
//            val vd = if (j==i) {dest + lmul - 1.U - i.U} else (VECTOR_TMP_REG_LMUL+j).U
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).srcType(0) := SrcType.fp
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).lsrc(0) := FP_TMP_REG_MV.U
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).lsrc(1) := src2 + lmul - 1.U - j.U
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).lsrc(2) := old_vd
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).ldest := vd
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).uopIdx := numOfUop-(i*(i+1)/2+i-j+2).U
//          }
//        }
//    }
//
//    is(UopSplitType.VEC_ISLIDEDOWN) {
//      // LMUL
//      for(i <- 0 until MAX_VLMUL)
//        for(j <- (0 to i).reverse){
//          when(i.U < lmul){
//            val old_vd = if (j==0) {dest + lmul -1.U - i.U} else (VECTOR_TMP_REG_LMUL+j-1).U
//            val vd = if (j==i) {dest + lmul - 1.U - i.U} else (VECTOR_TMP_REG_LMUL+j).U
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).lsrc(1) := src2 + lmul - 1.U - j.U
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).lsrc(2) := old_vd
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).ldest := vd
//            csBundle(numOfUop-(i*(i+1)/2+i-j+1).U).uopIdx := numOfUop-(i*(i+1)/2+i-j+1).U
//          }
//        }
//    }
//
//    is(UopSplitType.VEC_M0X) {
//      // LMUL
//      for (i <- 0 until MAX_VLMUL) {
//        val srcType0 = if (i==0) SrcType.DC else SrcType.vp
//        val ldest = (VECTOR_TMP_REG_LMUL + i).U
//        csBundle(i).srcType(0) := srcType0
//        csBundle(i).srcType(1) := SrcType.vp
//        csBundle(i).rfWen := false.B
//        csBundle(i).vecWen := true.B
//        csBundle(i).lsrc(0) := (VECTOR_TMP_REG_LMUL + i - 1).U
//        csBundle(i).lsrc(1) := src2
//        // csBundle(i).lsrc(2) := dest + i.U  DontCare
//        csBundle(i).ldest := ldest
//        csBundle(i).uopIdx := i.U
//      }
//      csBundle(lmul-1.U).vecWen := false.B
//      csBundle(lmul-1.U).fpWen := true.B
//      csBundle(lmul-1.U).ldest := FP_TMP_REG_MV.U
//      // FMV_X_D
//      csBundle(lmul).srcType(0) := SrcType.fp
//      csBundle(lmul).srcType(1) := SrcType.imm
//      csBundle(lmul).lsrc(0) := FP_TMP_REG_MV.U
//      csBundle(lmul).lsrc(1) := 0.U
//      csBundle(lmul).ldest := dest
//      csBundle(lmul).fuType := FuType.fmisc.U
//      csBundle(lmul).rfWen := true.B
//      csBundle(lmul).fpWen := false.B
//      csBundle(lmul).vecWen := false.B
//      csBundle(lmul).fpu.isAddSub := false.B
//      csBundle(lmul).fpu.typeTagIn := FPU.D
//      csBundle(lmul).fpu.typeTagOut := FPU.D
//      csBundle(lmul).fpu.fromInt := false.B
//      csBundle(lmul).fpu.wflags := false.B
//      csBundle(lmul).fpu.fpWen := false.B
//      csBundle(lmul).fpu.div := false.B
//      csBundle(lmul).fpu.sqrt := false.B
//      csBundle(lmul).fpu.fcvt := false.B
//    }
//
//    is(UopSplitType.VEC_MVV) {
//      // LMUL
//      for (i <- 0 until MAX_VLMUL) {
//        val srcType0 = if (i==0) SrcType.DC else SrcType.vp
//        csBundle(i*2+0).srcType(0) := srcType0
//        csBundle(i*2+0).srcType(1) := SrcType.vp
//        csBundle(i*2+0).lsrc(0) := (VECTOR_TMP_REG_LMUL + i - 1).U
//        csBundle(i*2+0).lsrc(1) := src2
//        csBundle(i*2+0).lsrc(2) := dest + i.U
//        csBundle(i*2+0).ldest := dest + i.U
//        csBundle(i*2+0).uopIdx := (i*2+0).U
//
//        csBundle(i*2+1).srcType(0) := srcType0
//        csBundle(i*2+1).srcType(1) := SrcType.vp
//        csBundle(i*2+1).lsrc(0) := (VECTOR_TMP_REG_LMUL + i - 1).U
//        csBundle(i*2+1).lsrc(1) := src2
//        // csBundle(i).lsrc(2) := dest + i.U  DontCare
//        csBundle(i*2+1).ldest := (VECTOR_TMP_REG_LMUL + i).U
//        csBundle(i*2+1).uopIdx := (i*2+1).U
//      }
//    }
//
//    is(UopSplitType.VEC_M0X_VFIRST) {
//      // LMUL
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      // FMV_X_D
//      csBundle(1).srcType(0) := SrcType.fp
//      csBundle(1).srcType(1) := SrcType.imm
//      csBundle(1).lsrc(0) := FP_TMP_REG_MV.U
//      csBundle(1).lsrc(1) := 0.U
//      csBundle(1).ldest := dest
//      csBundle(1).fuType := FuType.fmisc.U
//      csBundle(1).rfWen := true.B
//      csBundle(1).fpWen := false.B
//      csBundle(1).vecWen := false.B
//      csBundle(1).fpu.isAddSub := false.B
//      csBundle(1).fpu.typeTagIn := FPU.D
//      csBundle(1).fpu.typeTagOut := FPU.D
//      csBundle(1).fpu.fromInt := false.B
//      csBundle(1).fpu.wflags := false.B
//      csBundle(1).fpu.fpWen := false.B
//      csBundle(1).fpu.div := false.B
//      csBundle(1).fpu.sqrt := false.B
//      csBundle(1).fpu.fcvt := false.B
//    }
//
//    is(UopSplitType.VEC_VWW) {
//      for (i <- 0 until MAX_VLMUL*2) {
//        when(i.U < lmul){
//          csBundle(i).srcType(2) := SrcType.DC
//          csBundle(i).lsrc(0) := src2 + i.U
//          csBundle(i).lsrc(1) := src2 + i.U
//          // csBundle(i).lsrc(2) := dest + (2 * i).U
//          csBundle(i).ldest := (VECTOR_TMP_REG_LMUL + i).U
//          csBundle(i).uopIdx :=  i.U
//        } otherwise {
//          csBundle(i).srcType(2) := SrcType.DC
//          csBundle(i).lsrc(0) := VECTOR_TMP_REG_LMUL.U + Cat((i.U-lmul),0.U(1.W)) + 1.U
//          csBundle(i).lsrc(1) := VECTOR_TMP_REG_LMUL.U + Cat((i.U-lmul),0.U(1.W))
//          // csBundle(i).lsrc(2) := dest + (2 * i).U
//          csBundle(i).ldest := (VECTOR_TMP_REG_LMUL + i).U
//          csBundle(i).uopIdx := i.U
//        }
//        csBundle(numOfUop-1.U).srcType(2) := SrcType.vp
//        csBundle(numOfUop-1.U).lsrc(0) := src1
//        csBundle(numOfUop-1.U).lsrc(2) := dest
//        csBundle(numOfUop-1.U).ldest := dest
//      }
//    }
//
//    is(UopSplitType.VEC_RGATHER) {
//      def genCsBundle_VEC_RGATHER(len:Int): Unit ={
//        for (i <- 0 until len)
//          for (j <- 0 until len) {
//            // csBundle(i * len + j).srcType(0) := SrcType.vp // SrcType.imm
//            // csBundle(i * len + j).srcType(1) := SrcType.vp
//            // csBundle(i * len + j).srcType(2) := SrcType.vp
//            csBundle(i * len + j).lsrc(0) := src1 + i.U
//            csBundle(i * len + j).lsrc(1) := src2 + j.U
//            val vd_old = if(j==0) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j - 1).U
//            csBundle(i * len + j).lsrc(2) := vd_old
//            val vd = if(j==len-1) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j).U
//            csBundle(i * len + j).ldest := vd
//            csBundle(i * len + j).uopIdx := (i * len + j).U
//          }
//      }
//      switch(simple.io.enq.vtype.vlmul) {
//        is("b001".U ){
//          genCsBundle_VEC_RGATHER(2)
//        }
//
//        is("b010".U ){
//          genCsBundle_VEC_RGATHER(4)
//        }
//
//        is("b011".U ){
//          genCsBundle_VEC_RGATHER(8)
//        }
//      }
//    }
//
//    is(UopSplitType.VEC_RGATHER_VX) {
//      def genCsBundle_RGATHER_VX(len:Int): Unit ={
//        for (i <- 0 until len)
//          for (j <- 0 until len) {
//            csBundle(i * len + j + 1).srcType(0) := SrcType.fp
//            // csBundle(i * len + j + 1).srcType(1) := SrcType.vp
//            // csBundle(i * len + j + 1).srcType(2) := SrcType.vp
//            csBundle(i * len + j + 1).lsrc(0) := FP_TMP_REG_MV.U
//            csBundle(i * len + j + 1).lsrc(1) := src2 + j.U
//            val vd_old = if(j==0) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j - 1).U
//            csBundle(i * len + j + 1).lsrc(2) := vd_old
//            val vd = if(j==len-1) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j).U
//            csBundle(i * len + j + 1).ldest := vd
//            csBundle(i * len + j + 1).uopIdx := (i * len + j).U
//          }
//      }
//
//      // FMV.D.X
//      csBundle(0).srcType(0) := SrcType.reg
//      csBundle(0).srcType(1) := SrcType.imm
//      csBundle(0).lsrc(1) := 0.U
//      csBundle(0).ldest := FP_TMP_REG_MV.U
//      csBundle(0).fuType := FuType.i2f.U
//      csBundle(0).rfWen := false.B
//      csBundle(0).fpWen := true.B
//      csBundle(0).vecWen := false.B
//      csBundle(0).fpu.isAddSub := false.B
//      csBundle(0).fpu.typeTagIn := FPU.D
//      csBundle(0).fpu.typeTagOut := FPU.D
//      csBundle(0).fpu.fromInt := true.B
//      csBundle(0).fpu.wflags := false.B
//      csBundle(0).fpu.fpWen := true.B
//      csBundle(0).fpu.div := false.B
//      csBundle(0).fpu.sqrt := false.B
//      csBundle(0).fpu.fcvt := false.B
//
//      switch(simple.io.enq.vtype.vlmul) {
//        is("b000".U ){
//          genCsBundle_RGATHER_VX(1)
//        }
//
//        is("b001".U ){
//          genCsBundle_RGATHER_VX(2)
//        }
//
//        is("b010".U ){
//          genCsBundle_RGATHER_VX(4)
//        }
//
//        is("b011".U ){
//          genCsBundle_RGATHER_VX(8)
//        }
//      }
//    }
//
//    is(UopSplitType.VEC_RGATHEREI16) {
//      def genCsBundle_VEC_RGATHEREI16_SEW8(len:Int): Unit ={
//        for (i <- 0 until len)
//          for (j <- 0 until len) {
//            val vd_old0 = if(j==0) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j*2-1).U
//            val vd0 = (VECTOR_TMP_REG_LMUL + j*2 ).U
//            // csBundle(i * len + j).srcType(0) := SrcType.vp // SrcType.imm
//            // csBundle(i * len + j).srcType(1) := SrcType.vp
//            // csBundle(i * len + j).srcType(2) := SrcType.vp
//            csBundle((i * len + j)*2+0).lsrc(0) := src1 + (i*2+0).U
//            csBundle((i * len + j)*2+0).lsrc(1) := src2 + j.U
//            csBundle((i * len + j)*2+0).lsrc(2) := vd_old0
//            csBundle((i * len + j)*2+0).ldest := vd0
//            csBundle((i * len + j)*2+0).uopIdx := ((i * len + j)*2+0).U
//
//            val vd_old1 = (VECTOR_TMP_REG_LMUL + j*2).U
//            val vd1 = if(j==len-1) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j*2+1 ).U
//            csBundle((i * len + j)*2+1).lsrc(0) := src1 + (i*2+1).U
//            csBundle((i * len + j)*2+1).lsrc(1) := src2 + j.U
//            csBundle((i * len + j)*2+1).lsrc(2) := vd_old1
//            csBundle((i * len + j)*2+1).ldest := vd1
//            csBundle((i * len + j)*2+1).uopIdx := ((i * len + j)*2+1).U
//          }
//      }
//      def genCsBundle_VEC_RGATHEREI16(len:Int): Unit ={
//        for (i <- 0 until len)
//          for (j <- 0 until len) {
//            val vd_old = if(j==0) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j-1).U
//            val vd = if(j==len-1) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j).U
//            // csBundle(i * len + j).srcType(0) := SrcType.vp // SrcType.imm
//            // csBundle(i * len + j).srcType(1) := SrcType.vp
//            // csBundle(i * len + j).srcType(2) := SrcType.vp
//            csBundle(i * len + j).lsrc(0) := src1 + i.U
//            csBundle(i * len + j).lsrc(1) := src2 + j.U
//            csBundle(i * len + j).lsrc(2) := vd_old
//            csBundle(i * len + j).ldest := vd
//            csBundle(i * len + j).uopIdx := (i * len + j).U
//          }
//      }
//      switch(simple.io.enq.vtype.vlmul) {
//        is("b000".U ){
//          when(!simple.io.enq.vtype.vsew.orR){
//            genCsBundle_VEC_RGATHEREI16_SEW8(1)
//          } .otherwise{
//            genCsBundle_VEC_RGATHEREI16(1)
//          }
//        }
//
//        is("b001".U) {
//          when(!simple.io.enq.vtype.vsew.orR) {
//            genCsBundle_VEC_RGATHEREI16_SEW8(2)
//          }.otherwise {
//            genCsBundle_VEC_RGATHEREI16(2)
//          }
//        }
//
//        is("b010".U) {
//          when(!simple.io.enq.vtype.vsew.orR) {
//            genCsBundle_VEC_RGATHEREI16_SEW8(4)
//          }.otherwise {
//            genCsBundle_VEC_RGATHEREI16(4)
//          }
//        }
//
//        is("b011".U) {
//          genCsBundle_VEC_RGATHEREI16(8)
//        }
//      }
//    }
//
//    is(UopSplitType.VEC_COMPRESS) {
//      def genCsBundle_VEC_COMPRESS(len:Int): Unit ={
//        for (i <- 0 until len){
//          val jlen = if (i == len-1) i+1 else i+2
//          for (j <- 0 until jlen) {
//            val vd_old = if(i==j) (dest + i.U) else (VECTOR_TMP_REG_LMUL + j + 1).U
//            val vd = if(i==len-1) (dest + j.U) else{
//              if (j == i+1) VECTOR_TMP_REG_LMUL.U else (VECTOR_TMP_REG_LMUL + j + 1).U
//            }
//            val src23Type = if (j == i+1) DontCare else SrcType.vp
//            csBundle(i*(i+3)/2 + j).srcType(0) := SrcType.vp
//            csBundle(i*(i+3)/2 + j).srcType(1) := src23Type
//            csBundle(i*(i+3)/2 + j).srcType(2) := src23Type
//            csBundle(i*(i+3)/2 + j).lsrc(0) := src1
//            csBundle(i*(i+3)/2 + j).lsrc(1) := src2 + i.U
//            csBundle(i*(i+3)/2 + j).lsrc(2) := vd_old
//            // csBundle(i*(i+3)/2 + j).lsrc(3) := VECTOR_TMP_REG_LMUL.U
//            csBundle(i*(i+3)/2 + j).ldest := vd
//            csBundle(i*(i+3)/2 + j).uopIdx := (i*(i+3)/2 + j).U
//          }
//        }
//      }
//      switch(simple.io.enq.vtype.vlmul) {
//        is("b001".U ){
//          genCsBundle_VEC_COMPRESS(2)
//        }
//
//        is("b010".U ){
//          genCsBundle_VEC_COMPRESS(4)
//        }
//
//        is("b011".U ){
//          genCsBundle_VEC_COMPRESS(8)
//        }
//      }
//    }
//  }
//
//  //uops dispatch
//  val s_normal :: s_ext :: Nil = Enum(2)
//  val state = RegInit(s_normal)
//  val state_next = WireDefault(state)
//  val uopRes = RegInit(0.U)
//
//  //readyFromRename Counter
//  val readyCounter = PriorityMuxDefault(io.readyFromRename.map(x => !x).zip((0 to (RenameWidth - 1)).map(_.U)), RenameWidth.U)
//
//  switch(state) {
//    is(s_normal) {
//      state_next := Mux(io.validFromIBuf(0) && (numOfUop > readyCounter) && (readyCounter =/= 0.U), s_ext, s_normal)
//    }
//    is(s_ext) {
//      state_next := Mux(io.validFromIBuf(0) && (uopRes > readyCounter), s_ext, s_normal)
//    }
//  }
//
//  state := state_next
//
//  val uopRes0 = Mux(state === s_normal, numOfUop, uopRes)
//  val uopResJudge = Mux(state === s_normal,
//    io.validFromIBuf(0) && (readyCounter =/= 0.U) && (uopRes0 > readyCounter),
//    io.validFromIBuf(0) && (uopRes0 > readyCounter))
//  uopRes := Mux(uopResJudge, uopRes0 - readyCounter, 0.U)
//
//  for(i <- 0 until RenameWidth) {
//    decodedInsts(i) := MuxCase(csBundle(i), Seq(
//      (state === s_normal) -> csBundle(i),
//      (state === s_ext) -> Mux((i.U + numOfUop -uopRes) < maxNumOfUop.U, csBundle(i.U + numOfUop - uopRes), csBundle(maxNumOfUop - 1))
//    ))
//  }
//
//
//  val validSimple = Wire(Vec(DecodeWidth - 1, Bool()))
//  validSimple.zip(io.validFromIBuf.drop(1).zip(io.isComplex)).map{ case (dst, (src1, src2)) => dst := src1 && !src2 }
//  val notInf = Wire(Vec(DecodeWidth - 1, Bool()))
//  notInf.zip(io.validFromIBuf.drop(1).zip(validSimple)).map{ case (dst, (src1, src2)) => dst := !src1 || src2 }
//  val notInfVec = Wire(Vec(DecodeWidth, Bool()))
//  notInfVec.drop(1).zip(0 until DecodeWidth - 1).map{ case (dst, i) => dst := Cat(notInf.take(i + 1)).andR}
//  notInfVec(0) := true.B
//
//  complexNum := Mux(io.validFromIBuf(0) && readyCounter.orR ,
//    Mux(uopRes0 > readyCounter, readyCounter, uopRes0),
//    1.U)
//  validToRename.zipWithIndex.foreach{
//    case(dst, i) =>
//      dst := MuxCase(false.B, Seq(
//        (io.validFromIBuf(0) && uopRes0 > readyCounter   ) -> Mux(readyCounter > i.U, true.B, false.B),
//        (io.validFromIBuf(0) && !(uopRes0 > readyCounter)) -> Mux(complexNum > i.U, true.B, validSimple(i.U - complexNum) && notInfVec(i.U - complexNum) && io.readyFromRename(i)),
//      ))
//  }
//
//  readyToIBuf.zipWithIndex.foreach {
//    case (dst, i) =>
//      dst := MuxCase(true.B, Seq(
//        (io.validFromIBuf(0) && uopRes0 > readyCounter) -> false.B,
//        (io.validFromIBuf(0) && !(uopRes0 > readyCounter)) -> (if (i==0) true.B else Mux(RenameWidth.U - complexNum >= i.U, notInfVec(i - 1) && validSimple(i - 1) && io.readyFromRename(i), false.B)),
//      ))
//  }
//
//  io.deq.decodedInsts := decodedInsts
//  io.deq.isVset := isVsetSimple
//  io.deq.complexNum := complexNum
//  io.deq.validToRename := validToRename
//  io.deq.readyToIBuf := readyToIBuf
//
//}
//
