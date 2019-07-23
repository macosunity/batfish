package org.batfish.dataplane.ibdp;

import static java.util.Objects.requireNonNull;
import static org.batfish.common.util.CollectionUtil.toImmutableSortedMap;
import static org.batfish.common.util.CollectionUtil.toOrderedHashCode;
import static org.batfish.dataplane.rib.RibDelta.importRibDelta;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.graph.Network;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.AnnotatedRoute;
import org.batfish.datamodel.ConcreteInterfaceAddress;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.EigrpExternalRoute;
import org.batfish.datamodel.EigrpInternalRoute;
import org.batfish.datamodel.EigrpRoute;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.NetworkConfigurations;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.eigrp.EigrpEdge;
import org.batfish.datamodel.eigrp.EigrpMetric;
import org.batfish.datamodel.eigrp.EigrpNeighborConfigId;
import org.batfish.datamodel.eigrp.EigrpProcess;
import org.batfish.datamodel.eigrp.EigrpTopology;
import org.batfish.datamodel.routing_policy.Environment.Direction;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.dataplane.rib.EigrpExternalRib;
import org.batfish.dataplane.rib.EigrpInternalRib;
import org.batfish.dataplane.rib.EigrpRib;
import org.batfish.dataplane.rib.RibDelta;
import org.batfish.dataplane.rib.RibDelta.Builder;
import org.batfish.dataplane.rib.RouteAdvertisement;

/** An instance of an EigrpProcess as constructed and used by {@link VirtualRouter} */
@ParametersAreNonnullByDefault
final class EigrpRoutingProcess implements RoutingProcess<EigrpTopology, EigrpRoute> {

  private final long _asn;
  private final int _defaultExternalAdminCost;
  private final int _defaultInternalAdminCost;
  /** Helper RIB containing EIGRP external paths */
  @Nonnull private final EigrpExternalRib _externalRib;

  @Nonnull private final List<EigrpNeighborConfigId> _interfaces;
  /** Helper RIB containing all EIGRP paths internal to this router's ASN. */
  @Nonnull private final EigrpInternalRib _internalRib;

  @Nonnull private final String _vrfName;
  /** Helper RIBs containing EIGRP internal and external paths. */
  @Nonnull private final EigrpRib _rib;
  /** Routing policy to determine whether and how to export */
  @Nullable private final RoutingPolicy _exportPolicy;
  /** Incoming internal route messages into this router from each EIGRP adjacency */
  @Nonnull
  private SortedMap<EigrpEdge, Queue<RouteAdvertisement<EigrpInternalRoute>>>
      _incomingInternalRoutes;
  /** Incoming external route messages into this router from each EIGRP adjacency */
  @Nonnull @VisibleForTesting
  SortedMap<EigrpEdge, Queue<RouteAdvertisement<EigrpExternalRoute>>> _incomingExternalRoutes;

  /** Current known EIGRP topology */
  @Nonnull private EigrpTopology _topology;

  /** A {@link RibDelta} indicating which internal routes we initialized */
  @Nonnull private RibDelta<EigrpInternalRoute> _initializationDelta;

  /**
   * A {@link RibDelta} containing external routes we need to send/withdraw based on most recent
   * round of route redistribution
   */
  @Nonnull private RibDelta<EigrpExternalRoute> _queuedForRedistribution;
  /** Set of routes to be merged to the main RIB at the end of the iteration */
  @Nonnull private RibDelta.Builder<EigrpRoute> _changeSet;

  EigrpRoutingProcess(final EigrpProcess process, final String vrfName, final Configuration c) {
    _asn = process.getAsn();
    _defaultExternalAdminCost =
        RoutingProtocol.EIGRP_EX.getDefaultAdministrativeCost(c.getConfigurationFormat());
    _defaultInternalAdminCost =
        RoutingProtocol.EIGRP.getDefaultAdministrativeCost(c.getConfigurationFormat());
    _externalRib = new EigrpExternalRib();
    _interfaces = new ArrayList<>();
    _internalRib = new EigrpInternalRib();
    _rib = new EigrpRib();
    _vrfName = vrfName;

    // get EIGRP export policy name
    String exportPolicyName = process.getExportPolicy();
    _exportPolicy = exportPolicyName != null ? c.getRoutingPolicies().get(exportPolicyName) : null;
    _topology = EigrpTopology.EMPTY;
    _initializationDelta = RibDelta.empty();
    _queuedForRedistribution = RibDelta.empty();
    _incomingInternalRoutes = ImmutableSortedMap.of();
    _incomingExternalRoutes = ImmutableSortedMap.of();
    _changeSet = RibDelta.builder();
  }

  @Override
  public void initialize(Node n) {
    _initializationDelta = initInternalRoutes(_vrfName, n.getConfiguration());
  }

  @Override
  public void updateTopology(EigrpTopology topology) {
    _topology = topology;
    updateQueues(_topology);
    /*
    TODO:
      1. Send existing routes to new neighbors
      2. Remove routes received from edges that are now down
    */
  }

  @Override
  public void executeIteration(Map<String, Node> allNodes) {
    _changeSet = RibDelta.builder();
    if (!_initializationDelta.isEmpty()) {
      // If we haven't sent out the first round of updates after initialization, do so now. Then
      // clear the initialization delta
      sendOutInternalRoutes(_initializationDelta, allNodes);
      _initializationDelta = RibDelta.empty();
    }

    // TODO: optimize, don't recreate the map each iteration
    NetworkConfigurations nc =
        NetworkConfigurations.of(
            allNodes.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        Entry::getKey, e -> e.getValue().getConfiguration())));

    // Process internal routes
    RibDelta<EigrpInternalRoute> internalDelta = processInternalRoutes(nc);
    sendOutInternalRoutes(internalDelta, allNodes);

    // Send out anything we had queued for redistribution
    sendOutExternalRoutes(_queuedForRedistribution, allNodes);
    _queuedForRedistribution = RibDelta.empty();

    // Process new external routes and re-advertise them as necessary
    RibDelta<EigrpExternalRoute> externalDelta = processExternalRoutes(nc);
    sendOutExternalRoutes(externalDelta, allNodes);

    // Keep track of what what updates will go into the main RIB
    _changeSet.from(importRibDelta(_rib, internalDelta));
    _changeSet.from(importRibDelta(_rib, externalDelta));
  }

  @Nonnull
  @Override
  public RibDelta<EigrpRoute> getUpdatesForMainRib() {
    return _changeSet.build();
  }

  @Override
  public void redistribute(RibDelta<? extends AnnotatedRoute<AbstractRoute>> mainRibDelta) {
    RibDelta.Builder<EigrpExternalRoute> builder = RibDelta.builder();
    mainRibDelta
        .getActions()
        .forEach(
            ra -> {
              EigrpExternalRoute outputRoute = computeEigrpExportRoute(ra.getRoute());
              if (outputRoute == null) {
                return; // no need to export
              }
              if (!ra.isWithdrawn()) {
                builder.from(_externalRib.mergeRouteGetDelta(outputRoute));
              } else {
                builder.from(_externalRib.removeRouteGetDelta(outputRoute));
              }
            });
    _queuedForRedistribution = builder.build();
  }

  @Override
  public boolean isDirty() {
    return !_incomingInternalRoutes.values().stream().allMatch(Queue::isEmpty)
        || !_incomingExternalRoutes.values().stream().allMatch(Queue::isEmpty)
        || !_changeSet.isEmpty()
        || !_queuedForRedistribution.isEmpty()
        || !_initializationDelta.isEmpty();
  }

  /**
   * Init internal routes from connected routes. For each interface prefix, construct a new internal
   * route.
   */
  private RibDelta<EigrpInternalRoute> initInternalRoutes(String vrfName, Configuration c) {
    Builder<EigrpInternalRoute> builder = RibDelta.builder();
    for (String ifaceName : c.getVrfs().get(vrfName).getInterfaceNames()) {
      Interface iface = c.getAllInterfaces().get(ifaceName);
      if (!iface.getActive()
          || iface.getEigrp() == null
          || iface.getEigrp().getAsn() != _asn
          || !iface.getEigrp().getEnabled()) {
        continue;
      }
      _interfaces.add(new EigrpNeighborConfigId(iface.getEigrp().getAsn(), c.getHostname(), iface));
      requireNonNull(iface.getEigrp());
      Set<Prefix> allNetworkPrefixes =
          iface.getAllConcreteAddresses().stream()
              .map(ConcreteInterfaceAddress::getPrefix)
              .collect(Collectors.toSet());
      for (Prefix prefix : allNetworkPrefixes) {
        EigrpInternalRoute route =
            EigrpInternalRoute.builder()
                .setAdmin(
                    RoutingProtocol.EIGRP.getDefaultAdministrativeCost(c.getConfigurationFormat()))
                .setEigrpMetric(iface.getEigrp().getMetric())
                .setNetwork(prefix)
                .setProcessAsn(_asn)
                .build();
        builder.from(_internalRib.mergeRouteGetDelta(route));
      }
    }
    return builder.build();
  }

  @Nonnull
  private RibDelta<EigrpInternalRoute> processInternalRoutes(NetworkConfigurations nc) {
    // TODO: simplify all this later. Copied from old code
    Builder<EigrpInternalRoute> builder = RibDelta.builder();
    _incomingInternalRoutes.forEach(
        (edge, queue) -> {
          EigrpMetric connectingInterfaceMetric =
              edge.getNode2().getInterfaceSettings(nc).getMetric();
          Interface neighborInterface = edge.getNode1().getInterface(nc);
          Ip nextHopIp = neighborInterface.getConcreteAddress().getIp();
          while (!queue.isEmpty()) {
            RouteAdvertisement<EigrpInternalRoute> ra = queue.remove();
            EigrpInternalRoute route = ra.getRoute();
            EigrpMetric newMetric =
                connectingInterfaceMetric.accumulate(
                    neighborInterface.getEigrp().getMetric(), route.getEigrpMetric());
            EigrpInternalRoute transformedRoute =
                EigrpInternalRoute.builder()
                    .setAdmin(_defaultInternalAdminCost)
                    .setEigrpMetric(newMetric)
                    .setNetwork(route.getNetwork())
                    .setNextHopIp(nextHopIp)
                    .setProcessAsn(_asn)
                    .build();
            if (ra.isWithdrawn()) {
              builder.from(_internalRib.removeRouteGetDelta(transformedRoute));
            } else {
              builder.from(_internalRib.mergeRouteGetDelta(transformedRoute));
            }
          }
        });
    return builder.build();
  }

  @Nonnull
  private RibDelta<EigrpExternalRoute> processExternalRoutes(NetworkConfigurations nc) {
    // TODO: simplify all this later. Copied from old code
    RibDelta.Builder<EigrpExternalRoute> deltaBuilder = RibDelta.builder();
    EigrpExternalRoute.Builder routeBuilder = EigrpExternalRoute.builder();
    routeBuilder.setAdmin(_defaultExternalAdminCost).setProcessAsn(_asn);

    _incomingExternalRoutes.forEach(
        (edge, queue) -> {
          Interface nextHopIntf = edge.getNode1().getInterface(nc);
          Interface connectingIntf = edge.getNode2().getInterface(nc);

          // Edge nodes must have EIGRP configuration
          if (nextHopIntf.getEigrp() == null || connectingIntf.getEigrp() == null) {
            return;
          }

          EigrpMetric nextHopIntfMetric = nextHopIntf.getEigrp().getMetric();
          EigrpMetric connectingIntfMetric = connectingIntf.getEigrp().getMetric();

          routeBuilder.setNextHopIp(nextHopIntf.getConcreteAddress().getIp());
          while (queue.peek() != null) {
            RouteAdvertisement<EigrpExternalRoute> routeAdvert = queue.remove();
            EigrpExternalRoute neighborRoute = routeAdvert.getRoute();
            EigrpMetric metric =
                connectingIntfMetric.accumulate(nextHopIntfMetric, neighborRoute.getEigrpMetric());
            routeBuilder
                .setDestinationAsn(neighborRoute.getDestinationAsn())
                .setEigrpMetric(metric)
                .setNetwork(neighborRoute.getNetwork());
            EigrpExternalRoute transformedRoute = routeBuilder.build();

            if (routeAdvert.isWithdrawn()) {
              deltaBuilder.from(_externalRib.removeRouteGetDelta(transformedRoute));
            } else {
              deltaBuilder.from(_externalRib.mergeRouteGetDelta(transformedRoute));
            }
          }
        });

    return deltaBuilder.build();
  }

  private void sendOutInternalRoutes(
      RibDelta<EigrpInternalRoute> initializationDelta, Map<String, Node> allNodes) {
    for (EigrpEdge eigrpEdge : _incomingInternalRoutes.keySet()) {
      EigrpRoutingProcess neighborProc = getNeighborEigrpProcess(allNodes, eigrpEdge, _asn);
      neighborProc.enqueueInternalMessages(eigrpEdge.reverse(), initializationDelta.getActions());
    }
  }

  private void sendOutExternalRoutes(
      RibDelta<EigrpExternalRoute> queuedForRedistribution, Map<String, Node> allNodes) {
    for (EigrpEdge eigrpEdge : _incomingExternalRoutes.keySet()) {
      EigrpRoutingProcess neighborProc = getNeighborEigrpProcess(allNodes, eigrpEdge, _asn);
      neighborProc.enqueueExternalMessages(
          eigrpEdge.reverse(), queuedForRedistribution.getActions());
    }
  }

  /**
   * Computes an exportable EIGRP route from policy and existing routes
   *
   * @param potentialExportRoute Route to consider exporting
   * @return The computed export route or null if no route will be exported
   */
  @Nullable
  private EigrpExternalRoute computeEigrpExportRoute(
      AnnotatedRoute<AbstractRoute> potentialExportRoute) {
    AbstractRoute unannotatedPotentialRoute = potentialExportRoute.getRoute();
    EigrpExternalRoute.Builder outputRouteBuilder = EigrpExternalRoute.builder();
    // Set the metric to match the route metric by default for EIGRP into EIGRP
    if (unannotatedPotentialRoute instanceof EigrpRoute) {
      outputRouteBuilder.setEigrpMetric(((EigrpRoute) unannotatedPotentialRoute).getEigrpMetric());
    }
    // Export based on the policy result of processing the potentialExportRoute
    boolean accept =
        _exportPolicy != null
            && _exportPolicy.process(
                potentialExportRoute, outputRouteBuilder, null, _vrfName, Direction.OUT);
    if (!accept) {
      return null;
    }
    outputRouteBuilder.setAdmin(_defaultExternalAdminCost);
    if (unannotatedPotentialRoute instanceof EigrpExternalRoute) {
      EigrpExternalRoute externalRoute = (EigrpExternalRoute) unannotatedPotentialRoute;
      outputRouteBuilder.setDestinationAsn(externalRoute.getDestinationAsn());
    } else {
      outputRouteBuilder.setDestinationAsn(_asn);
    }
    outputRouteBuilder.setNetwork(unannotatedPotentialRoute.getNetwork());
    outputRouteBuilder.setProcessAsn(_asn);
    outputRouteBuilder.setNonRouting(true);
    return outputRouteBuilder.build();
  }

  /**
   * Compute the "hashcode" of this router for the iBDP purposes. The hashcode is computed from the
   * following data structures:
   *
   * <ul>
   *   <li>"external" RIB ({@link #_externalRib})
   *   <li>message queue ({@link #_incomingExternalRoutes})
   * </ul>
   *
   * @return integer hashcode
   */
  int computeIterationHashCode() {
    return Stream.of(
            _rib,
            _incomingInternalRoutes.values().stream(),
            _incomingExternalRoutes.values().stream())
        .collect(toOrderedHashCode());
  }

  long getAsn() {
    return _asn;
  }

  /**
   * Initialize incoming EIGRP message queues for each adjacency
   *
   * @param eigrpTopology The topology representing EIGRP adjacencies
   */
  void updateQueues(EigrpTopology eigrpTopology) {
    Network<EigrpNeighborConfigId, EigrpEdge> network = eigrpTopology.getNetwork();
    _incomingExternalRoutes =
        _interfaces.stream()
            .filter(network.nodes()::contains)
            .flatMap(n -> network.inEdges(n).stream())
            .collect(toImmutableSortedMap(Function.identity(), e -> new ConcurrentLinkedQueue<>()));
    _incomingInternalRoutes =
        _interfaces.stream()
            .filter(network.nodes()::contains)
            .flatMap(n -> network.inEdges(n).stream())
            .collect(toImmutableSortedMap(Function.identity(), e -> new ConcurrentLinkedQueue<>()));
  }

  /**
   * Get the neighboring EIGRP process correspoding to the tail node of {@code edge}
   *
   * @throws IllegalStateException if the EIGRP process cannot be found
   */
  @Nonnull
  private static EigrpRoutingProcess getNeighborEigrpProcess(
      Map<String, Node> allNodes, EigrpEdge edge, long asn) {
    return Optional.ofNullable(allNodes.get(edge.getNode1().getHostname()))
        .map(Node::getVirtualRouters)
        .map(vrs -> vrs.get(edge.getNode1().getVrf()))
        .map(vrf -> vrf.getEigrpProcess(asn))
        .orElseThrow(
            () -> new IllegalStateException("Cannot find EigrpProcess for " + edge.getNode1()));
  }

  private void enqueueInternalMessages(
      EigrpEdge edge, Stream<RouteAdvertisement<EigrpInternalRoute>> routes) {
    Queue<RouteAdvertisement<EigrpInternalRoute>> queue = _incomingInternalRoutes.get(edge);
    assert queue != null;
    routes.forEach(queue::add);
  }

  private void enqueueExternalMessages(
      EigrpEdge edge, Stream<RouteAdvertisement<EigrpExternalRoute>> routes) {
    Queue<RouteAdvertisement<EigrpExternalRoute>> queue = _incomingExternalRoutes.get(edge);
    assert queue != null;
    routes.forEach(queue::add);
  }
}