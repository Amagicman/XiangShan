package xiangshan.mem

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.cache._
import bus.tilelink.{TLArbiter, TLCached, TLMasterUtilities, TLParameters}

object LSUOpType {
  def lb   = "b000000".U
  def lh   = "b000001".U
  def lw   = "b000010".U
  def ld   = "b000011".U
  def lbu  = "b000100".U
  def lhu  = "b000101".U
  def lwu  = "b000110".U
  def ldu  = "b000111".U
  def sb   = "b001000".U
  def sh   = "b001001".U
  def sw   = "b001010".U
  def sd   = "b001011".U
  
  def lr      = "b100010".U
  def sc      = "b100011".U
  def amoswap = "b100001".U
  def amoadd  = "b100000".U
  def amoxor  = "b100100".U
  def amoand  = "b101100".U
  def amoor   = "b101000".U
  def amomin  = "b110000".U
  def amomax  = "b110100".U
  def amominu = "b111000".U
  def amomaxu = "b111100".U
  
  def isStore(func: UInt): Bool = func(3)
  def isAtom(func: UInt): Bool = func(5)
  
  def atomW = "010".U
  def atomD = "011".U
}

object DCacheMiscType {
  def miss      = "b00".U
  def mmio      = "b01".U
  def misc      = "b10".U
}

object genWmask {
  def apply(addr: UInt, sizeEncode: UInt): UInt = {
    (LookupTree(sizeEncode, List(
      "b00".U -> 0x1.U, //0001 << addr(2:0)
      "b01".U -> 0x3.U, //0011
      "b10".U -> 0xf.U, //1111
      "b11".U -> 0xff.U //11111111
    )) << addr(2, 0)).asUInt()
  }
}

object genWdata {
  def apply(data: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode, List(
      "b00".U -> Fill(8, data(7, 0)),
      "b01".U -> Fill(4, data(15, 0)),
      "b10".U -> Fill(2, data(31, 0)),
      "b11".U -> data
    ))
  }
}

class LsPipelineBundle extends XSBundle {
  val vaddr = UInt(VAddrBits.W)
  val paddr = UInt(PAddrBits.W)
  val func = UInt(6.W)
  val mask = UInt(8.W)
  val data = UInt(XLEN.W)
  val uop = new MicroOp

  val miss = Bool()
  val mmio = Bool()
  val rollback = Bool()

  val forwardMask = Vec(8, Bool())
  val forwardData = Vec(8, UInt(8.W))
}

class LoadForwardQueryIO extends XSBundle {
  val paddr = Output(UInt(PAddrBits.W))
  val mask = Output(UInt(8.W))
  val lsroqIdx = Output(UInt(LsroqIdxWidth.W))
  val uop = Output(new MicroOp) // for replay
  val pc = Output(UInt(VAddrBits.W)) //for debug
  val valid = Output(Bool()) //for debug

  val forwardMask = Input(Vec(8, Bool()))
  val forwardData = Input(Vec(8, UInt(8.W)))
}

class MemToBackendIO extends XSBundle {
  val ldin = Vec(exuParameters.LduCnt, Flipped(Decoupled(new ExuInput)))
  val stin = Vec(exuParameters.StuCnt, Flipped(Decoupled(new ExuInput)))
  val ldout = Vec(exuParameters.LduCnt, Decoupled(new ExuOutput))
  val stout = Vec(exuParameters.StuCnt, Decoupled(new ExuOutput))
  val redirect = Flipped(ValidIO(new Redirect))
  // replay all instructions form dispatch
  val replayAll = ValidIO(new Redirect)
  // replay mem instructions form Load Queue/Store Queue
  val tlbFeedback = Vec(exuParameters.LduCnt + exuParameters.LduCnt, ValidIO(new TlbFeedback))
  val commits = Flipped(Vec(CommitWidth, Valid(new RoqCommit)))
  val dp1Req = Vec(RenameWidth, Flipped(DecoupledIO(new MicroOp)))
  val lsroqIdxs = Output(Vec(RenameWidth, UInt(LsroqIdxWidth.W)))
}

class Memend extends XSModule {
  val io = IO(new Bundle{
    val backend = new MemToBackendIO
    val mem = new TLCached(l1BusParams)
    val mmio = new TLCached(l1BusParams)
  })

  val loadUnits = (0 until exuParameters.LduCnt).map(_ => Module(new LoadUnit))
  val storeUnits = (0 until exuParameters.StuCnt).map(_ => Module(new StoreUnit))
  val miscUnit = Module(new MiscUnit)
  val dcache = Module(new DCache)
  val uncache = Module(new Uncache)
  // val mshq = Module(new MSHQ)
  val dtlb = Module(new TLB(Width = DTLBWidth, isDtlb = true))
  val ptw = Module(new PTW)
  val lsroq = Module(new Lsroq)
  val sbuffer = Module(new FakeSbuffer)

  dcache.io.bus <> io.mem
  uncache.io.bus <> io.mmio
  dcache.io.lsu.misc <> DontCare
  dcache.io.ptw <> ptw.io.mem // TODO: ptw.io.mem
  ptw.io.tlb(0) <> dtlb.io.ptw
  ptw.io.tlb(1) <> DontCare //mem.io.itlb

  // LoadUnit
  for (i <- 0 until exuParameters.LduCnt) {
    loadUnits(i).io.ldin <> io.backend.ldin(i)
    loadUnits(i).io.ldout <> io.backend.ldout(i)
    loadUnits(i).io.redirect <> io.backend.redirect
    loadUnits(i).io.tlbFeedback <> io.backend.tlbFeedback(i)
    loadUnits(i).io.dcache <> dcache.io.lsu.load(i)
    loadUnits(i).io.dtlb <> dtlb.io.requestor(i)
    loadUnits(i).io.sbuffer <> sbuffer.io.forward(i)
    
    lsroq.io.loadIn(i) <> loadUnits(i).io.lsroq.loadIn
    lsroq.io.ldout(i) <> loadUnits(i).io.lsroq.ldout
    lsroq.io.forward(i) <> loadUnits(i).io.lsroq.forward
  }
  
  // StoreUnit
  for (i <- 0 until exuParameters.StuCnt) {
    storeUnits(i).io.stin <> io.backend.stin(i)
    storeUnits(i).io.redirect <> io.backend.redirect
    storeUnits(i).io.tlbFeedback <> io.backend.tlbFeedback(exuParameters.LduCnt + i)
    storeUnits(i).io.dtlb <> dtlb.io.requestor(exuParameters.LduCnt + i) // FIXME
    storeUnits(i).io.lsroq <> lsroq.io.storeIn(i)
  }

  sbuffer.io.dcache <> dcache.io.lsu.store

  lsroq.io.stout <> io.backend.stout
  lsroq.io.commits <> io.backend.commits
  lsroq.io.dp1Req <> io.backend.dp1Req
  lsroq.io.lsroqIdxs <> io.backend.lsroqIdxs
  lsroq.io.brqRedirect := io.backend.redirect
  io.backend.replayAll <> lsroq.io.rollback
  
  lsroq.io.dcache <> dcache.io.lsu.lsroq // TODO: Add AMO
  lsroq.io.uncache <> uncache.io.lsroq
  // LSROQ to store buffer
  lsroq.io.sbuffer <> sbuffer.io.in
  
  // MiscUnit
  // MiscUnit will override other control signials,
  // as misc insts (LR/SC/AMO) will block the pipeline  
  miscUnit.io <> DontCare
  miscUnit.io.in.bits := Mux(io.backend.ldin(0).valid, io.backend.ldin(0).bits, io.backend.ldin(1).bits) 
  miscUnit.io.in.valid := io.backend.ldin(0).valid && io.backend.ldin(0).bits.uop.ctrl.fuType === FuType.mou || 
    io.backend.ldin(1).valid && io.backend.ldin(1).bits.uop.ctrl.fuType === FuType.mou
  when(miscUnit.io.dtlb.req.valid){
    dtlb.io.requestor(0) <> miscUnit.io.dtlb // TODO: check it later
  }
  when(miscUnit.io.dcache.req.valid){
    dcache.io.lsu.lsroq.req <> miscUnit.io.dcache.req 
  }
  when(dcache.io.lsu.lsroq.resp.valid && dcache.io.lsu.lsroq.resp.bits.meta.id(1, 0) === DCacheMiscType.misc){
    dcache.io.lsu.lsroq.resp <> miscUnit.io.dcache.resp
  }
  when(miscUnit.io.out.valid){
    io.backend.ldout(0) <> miscUnit.io.out
  }
  miscUnit.io.out.ready := true.B
}
