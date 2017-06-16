//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson
package cleanup

import nelson.routing.{RoutePath, RoutingGraph, RoutingTable}

import scalaz.concurrent.Task
import scalaz.stream.{Channel, Process, channel, time}
import scalaz._
import Scalaz._
import quiver._
import java.time.Instant

import Datacenter.{Deployment, Namespace}
import journal.Logger

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Random
import scalaz.stream.async.immutable.Signal

final case class DeploymentCtx(deployment: Deployment, status: DeploymentStatus, exp: Option[Instant])

object CleanupCron {
  import nelson.storage.{run => runs, StoreOp,StoreOpF}
  import nelson.Datacenter.{Namespace,Deployment}

  private val log = Logger[CleanupCron.type]

  // exclude Terminated deployments because they don't need to be cleaned up
  private val status =
    DeploymentStatus.all.toList.filter(_ != DeploymentStatus.Terminated).toNel.yolo("can't be empty")

  /* Gets all deployments (exluding terminated) and routing graph for a namespace */
  def getDeploymentsForNamespace(ns: Namespace): StoreOpF[Vector[(Namespace,DeploymentCtx)]] =
    for {
      dps <- StoreOp.listDeploymentsForNamespaceByStatus(ns.id, status)
      dcx <- dps.toVector.traverse {
        case (d,s) => StoreOp.findDeploymentExpiration(d.id).map(DeploymentCtx(d,s,_))
      }
    } yield dcx.map(d => (ns,d))

  /* Gets all deployment (excluding terminated) and routing graph for each namespace within a Datacenter. */
  def getDeploymentsForDatacenter(dc: Datacenter): StoreOpF[Vector[(Datacenter,Namespace,DeploymentCtx,RoutingGraph)]] =
    for {
      _  <- log.debug(s"cleanup cron running for ${dc.name}").point[StoreOpF]
      ns <- StoreOp.listNamespacesForDatacenter(dc.name)
      gr <- RoutingTable.generateRoutingTables(dc.name)
      ds <- ns.toVector.traverseM(ns => getDeploymentsForNamespace(ns))
    } yield ds.flatMap { case (ns, dcx) => gr.find(_._1 == ns).map(x => (dc,ns,dcx,x._2)) }

  /* Gets all deployments (excluding terminated) for all datacenters and namespaces */
  def getDeployments(cfg: NelsonConfig): Task[Vector[(Datacenter,Namespace,DeploymentCtx,RoutingGraph)]] =
    runs(cfg.storage, cfg.datacenters.toVector.traverseM(dc => getDeploymentsForDatacenter(dc)))

  def process(cfg: NelsonConfig): Process[Task, (Datacenter,Namespace,Deployment)] =
    Process.eval(getDeployments(cfg)).flatMap(Process.emitAll)
      .through(ExpirationPolicyProcess.expirationProcess(cfg))
      .filter(x => GarbageCollector.expired(x._3)) // mark only expired deployments
      .through(GarbageCollector.mark(cfg))

  def refresh(cfg: NelsonConfig): Process[Task,Duration] =
    Process.repeatEval(Task.delay(cfg.cleanup.cleanupDelay)).flatMap(d =>
      time.awakeEvery(d)(cfg.pools.schedulingExecutor, cfg.pools.schedulingPool).once)

  /*
   * This is the entry point for the cleanup pipeline. The pipeline is run at
   * a predetermined cadence and is responsible for expiration policy management,
   * marking deploymnets as garbage and running the destroy workflow
   * to decommission deployments.
   */
  def pipeline(cfg: NelsonConfig): Process[Task, Unit] =
    (refresh(cfg) >> process(cfg).attempt()).observeW(cfg.auditor.errorSink).stripW.to(Reaper.reap(cfg))
}