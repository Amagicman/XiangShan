/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan

import chipsalliance.rocketchip.config
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{BundleBridgeSource, LazyModule, LazyModuleImp}
import freechips.rocketchip.interrupts.{IntSinkNode, IntSinkPortSimple}
import freechips.rocketchip.tile.HasFPUParameters
import freechips.rocketchip.tilelink.TLBuffer
import system.HasSoCParameter
import utility._
import utils._
import xiangshan.backend._
import xiangshan.cache.mmu._
import xiangshan.frontend._
import xiangshan.v2backend._

abstract class XSModule(implicit val p: Parameters) extends Module
  with HasXSParameter
  with HasFPUParameters

//remove this trait after impl module logic
trait NeedImpl {
  this: RawModule =>
  override protected def IO[T <: Data](iodef: T): T = {
    println(s"[Warn]: (${this.name}) please reomve 'NeedImpl' after implement this module")
    val io = chisel3.experimental.IO(iodef)
    io <> DontCare
    io
  }
}

//class WritebackSourceParams(
//  var exuConfigs: Seq[Seq[ExuConfig]] = Seq()
// ) {
//  def length: Int = exuConfigs.length
//  def ++(that: WritebackSourceParams): WritebackSourceParams = {
//    new WritebackSourceParams(exuConfigs ++ that.exuConfigs)
//  }
//}

//trait HasWritebackSource {
//  val writebackSourceParams: Seq[WritebackSourceParams]
//  final def writebackSource(sourceMod: HasWritebackSourceImp): Seq[Seq[Valid[ExuOutput]]] = {
//    require(sourceMod.writebackSource.isDefined, "should not use Valid[ExuOutput]")
//    val source = sourceMod.writebackSource.get
//    require(source.length == writebackSourceParams.length, "length mismatch between sources")
//    for ((s, p) <- source.zip(writebackSourceParams)) {
//      require(s.length == p.length, "params do not match with the exuOutput")
//    }
//    source
//  }
//  final def writebackSource1(sourceMod: HasWritebackSourceImp): Seq[Seq[DecoupledIO[ExuOutput]]] = {
//    require(sourceMod.writebackSource1.isDefined, "should not use DecoupledIO[ExuOutput]")
//    val source = sourceMod.writebackSource1.get
//    require(source.length == writebackSourceParams.length, "length mismatch between sources")
//    for ((s, p) <- source.zip(writebackSourceParams)) {
//      require(s.length == p.length, "params do not match with the exuOutput")
//    }
//    source
//  }
//  val writebackSourceImp: HasWritebackSourceImp
//}

//trait HasWritebackSourceImp {
//  def writebackSource: Option[Seq[Seq[Valid[ExuOutput]]]] = None
//  def writebackSource1: Option[Seq[Seq[DecoupledIO[ExuOutput]]]] = None
//}

//trait HasWritebackSink {
//  // Caches all sources. The selected source will be the one with smallest length.
//  var writebackSinks = ListBuffer.empty[(Seq[HasWritebackSource], Seq[Int])]
//  def addWritebackSink(source: Seq[HasWritebackSource], index: Option[Seq[Int]] = None): HasWritebackSink = {
//    val realIndex = if (index.isDefined) index.get else Seq.fill(source.length)(0)
//    writebackSinks += ((source, realIndex))
//    this
//  }
//
//  def writebackSinksParams: Seq[WritebackSourceParams] = {
//    writebackSinks.map{ case (s, i) => s.zip(i).map(x => x._1.writebackSourceParams(x._2)).reduce(_ ++ _) }
//  }
//  final def writebackSinksMod(
//     thisMod: Option[HasWritebackSource] = None,
//     thisModImp: Option[HasWritebackSourceImp] = None
//   ): Seq[Seq[HasWritebackSourceImp]] = {
//    require(thisMod.isDefined == thisModImp.isDefined)
//    writebackSinks.map(_._1.map(source =>
//      if (thisMod.isDefined && source == thisMod.get) thisModImp.get else source.writebackSourceImp)
//    )
//  }
//  final def writebackSinksImp(
//    thisMod: Option[HasWritebackSource] = None,
//    thisModImp: Option[HasWritebackSourceImp] = None
//  ): Seq[Seq[ValidIO[ExuOutput]]] = {
//    val sourceMod = writebackSinksMod(thisMod, thisModImp)
//    writebackSinks.zip(sourceMod).map{ case ((s, i), m) =>
//      s.zip(i).zip(m).flatMap(x => x._1._1.writebackSource(x._2)(x._1._2))
//    }
//  }
//  def selWritebackSinks(func: WritebackSourceParams => Int): Int = {
//    writebackSinksParams.zipWithIndex.minBy(params => func(params._1))._2
//  }
//  def generateWritebackIO(
//    thisMod: Option[HasWritebackSource] = None,
//    thisModImp: Option[HasWritebackSourceImp] = None
//   ): Unit
//}

abstract class XSBundle(implicit val p: Parameters) extends Bundle
  with HasXSParameter

abstract class XSCoreBase()(implicit p: config.Parameters) extends LazyModule
  with HasXSParameter
{
  // interrupt sinks
  val clint_int_sink = IntSinkNode(IntSinkPortSimple(1, 2))
  val debug_int_sink = IntSinkNode(IntSinkPortSimple(1, 1))
  val plic_int_sink = IntSinkNode(IntSinkPortSimple(2, 1))
  // outer facing nodes
  val frontend = LazyModule(new Frontend())
  val ptw = LazyModule(new L2TLBWrapper())
  val ptw_to_l2_buffer = if (!coreParams.softPTW) LazyModule(new TLBuffer) else null
  val csrOut = BundleBridgeSource(Some(() => new DistributedCSRIO()))
  val backend = LazyModule(new Backend(backendParams))

  if (!coreParams.softPTW) {
    ptw_to_l2_buffer.node := ptw.node
  }

  val memBlock = LazyModule(new MemBlock()(p.alter((site, here, up) => {
    case XSCoreParamsKey => up(XSCoreParamsKey).copy(
      IssQueSize = 16 // Todo
    )
  })))
}

class XSCore()(implicit p: config.Parameters) extends XSCoreBase
  with HasXSDts
{
  lazy val module = new XSCoreImp(this)
}

class XSCoreImp(outer: XSCoreBase) extends LazyModuleImp(outer)
  with HasXSParameter
  with HasSoCParameter {
  val io = IO(new Bundle {
    val hartId = Input(UInt(64.W))
    val reset_vector = Input(UInt(PAddrBits.W))
    val cpu_halt = Output(Bool())
    val l2_pf_enable = Output(Bool())
    val perfEvents = Input(Vec(numPCntHc * coreParams.L2NBanks, new PerfEvent))
    val beu_errors = Output(new XSL1BusErrors())
  })

  println(s"FPGAPlatform:${env.FPGAPlatform} EnableDebug:${env.EnableDebug}")

  val frontend = outer.frontend.module
  val backend = outer.backend.module
  val memBlock = outer.memBlock.module
  val ptw = outer.ptw.module
  val ptw_to_l2_buffer = if (!coreParams.softPTW) outer.ptw_to_l2_buffer.module else null

  val fenceio = backend.io.fenceio

  frontend.io.hartId  := io.hartId
  frontend.io.backend <> backend.io.frontend
  frontend.io.sfence <> backend.io.frontendSfence
  frontend.io.tlbCsr <> backend.io.frontendTlbCsr
  frontend.io.csrCtrl <> backend.io.frontendCsrCtrl
  frontend.io.fencei <> fenceio.fencei

  backend.io.fromTop.hartId := io.hartId
  backend.io.fromTop.externalInterrupt.msip := outer.clint_int_sink.in.head._1(0)
  backend.io.fromTop.externalInterrupt.mtip := outer.clint_int_sink.in.head._1(1)
  backend.io.fromTop.externalInterrupt.meip := outer.plic_int_sink.in.head._1(0)
  backend.io.fromTop.externalInterrupt.seip := outer.plic_int_sink.in.last._1(0)
  backend.io.fromTop.externalInterrupt.debug := outer.debug_int_sink.in.head._1(0)

  backend.io.frontendCsrDistributedUpdate := frontend.io.csrUpdate

  backend.io.mem.stIn.zip(memBlock.io.stIn).foreach { case (sink, source) =>
    sink.valid := source.valid
    sink.bits := 0.U.asTypeOf(sink.bits)
    sink.bits.robIdx := source.bits.uop.robIdx
    sink.bits.ssid := source.bits.uop.ssid
    sink.bits.storeSetHit := source.bits.uop.storeSetHit
    // The other signals have not been used
  }
  backend.io.mem.memoryViolation <> memBlock.io.memoryViolation
  backend.io.mem.lsqEnqIO <> memBlock.io.enqLsq
  backend.io.mem.sqDeq := memBlock.io.sqDeq
  backend.io.mem.lqCancelCnt := memBlock.io.lqCancelCnt
  backend.io.mem.sqCancelCnt := memBlock.io.sqCancelCnt
  backend.io.mem.otherFastWakeup := memBlock.io.otherFastWakeup
  backend.io.mem.writeBack <> memBlock.io.writeback

  frontend.io.reset_vector := io.reset_vector

  io.cpu_halt := backend.io.toTop.cpuHalted

  // memblock error exception writeback, 1 cycle after normal writeback
  backend.io.mem.s3_delayed_load_error <> memBlock.io.s3_delayed_load_error

  io.beu_errors.icache <> frontend.io.error.toL1BusErrorUnitInfo()
  io.beu_errors.dcache <> memBlock.io.error.toL1BusErrorUnitInfo()

  memBlock.io.hartId := io.hartId
  memBlock.io.issue <> backend.io.mem.issueUops
  // By default, instructions do not have exceptions when they enter the function units.
  memBlock.io.issue.map(_.bits.uop.clearExceptions())
  backend.io.mem.loadFastMatch <> memBlock.io.loadFastMatch
  backend.io.mem.loadFastImm <> memBlock.io.loadFastImm
  backend.io.mem.exceptionVAddr := memBlock.io.lsqio.exceptionAddr.vaddr
  backend.io.mem.csrDistributedUpdate := memBlock.io.csrUpdate

  backend.io.perf.frontendInfo := frontend.io.frontendInfo
  backend.io.perf.memInfo := memBlock.io.memInfo
  backend.io.perf.perfEventsFrontend := frontend.getPerf
  backend.io.perf.perfEventsLsu := memBlock.getPerf
  backend.io.perf.perfEventsHc := io.perfEvents

  //  XSPerfHistogram("fastIn_count", PopCount(allFastUop1.map(_.valid)), true.B, 0, allFastUop1.length, 1)
//  XSPerfHistogram("wakeup_count", PopCount(rfWriteback.map(_.valid)), true.B, 0, rfWriteback.length, 1)

//  ctrlBlock.perfinfo.perfEventsEu0 := intExuBlock.getPerf.dropRight(outer.intExuBlock.scheduler.numRs)
//  ctrlBlock.perfinfo.perfEventsEu1 := vecExuBlock.getPerf.dropRight(outer.vecExuBlock.scheduler.numRs)
  if (!coreParams.softPTW) {
    memBlock.io.perfEventsPTW := ptw.getPerf
  } else {
    memBlock.io.perfEventsPTW := DontCare
  }
//  ctrlBlock.perfinfo.perfEventsRs  := outer.exuBlocks.flatMap(b => b.module.getPerf.takeRight(b.scheduler.numRs))

  memBlock.io.sfence <> backend.io.mem.sfence
  memBlock.io.fenceToSbuffer <> backend.io.mem.toSbuffer

  memBlock.io.redirect <> backend.io.mem.redirect
  memBlock.io.rsfeedback <> backend.io.mem.rsFeedBack
  memBlock.io.csrCtrl <> backend.io.mem.csrCtrl
  memBlock.io.tlbCsr <> backend.io.mem.tlbCsr
  memBlock.io.lsqio.rob <> backend.io.mem.robLsqIO
  memBlock.io.lsqio.exceptionAddr.isStore := backend.io.mem.isStoreException

  val itlbRepeater1 = PTWFilter(itlbParams.fenceDelay,frontend.io.ptw, fenceio.sfence, backend.io.tlb, l2tlbParams.ifilterSize)
  val itlbRepeater2 = PTWRepeaterNB(passReady = false, itlbParams.fenceDelay, itlbRepeater1.io.ptw, ptw.io.tlb(0), fenceio.sfence, backend.io.tlb)
  val dtlbRepeater1  = PTWFilter(ldtlbParams.fenceDelay, memBlock.io.ptw, fenceio.sfence, backend.io.tlb, l2tlbParams.dfilterSize)
  val dtlbRepeater2  = PTWRepeaterNB(passReady = false, ldtlbParams.fenceDelay, dtlbRepeater1.io.ptw, ptw.io.tlb(1), fenceio.sfence, backend.io.tlb)
  ptw.io.sfence <> fenceio.sfence
  ptw.io.csr.tlb <> backend.io.tlb
  ptw.io.csr.distribute_csr <> backend.io.csrCustomCtrl.distribute_csr

  // if l2 prefetcher use stream prefetch, it should be placed in XSCore
  io.l2_pf_enable := backend.io.csrCustomCtrl.l2_pf_enable

  // Modules are reset one by one
  val resetTree = ResetGenNode(
    Seq(
      ModuleNode(memBlock), ModuleNode(dtlbRepeater1),
      ResetGenNode(Seq(
        ModuleNode(itlbRepeater2),
        ModuleNode(ptw),
        ModuleNode(dtlbRepeater2),
        ModuleNode(ptw_to_l2_buffer),
      )),
      ResetGenNode(Seq(
        ModuleNode(backend),
        ResetGenNode(Seq(
          ResetGenNode(Seq(
            ModuleNode(frontend), ModuleNode(itlbRepeater1)
          ))
        ))
      ))
    )
  )

  ResetGen(resetTree, reset, !debugOpts.FPGAPlatform)

}
