package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.CircularDependencyException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dependency validation engine for groups and VMs.
 * Handles circular dependency detection, topological sorting, and dependency satisfaction checks.
 */
@Component
public class DependencyValidator {

    private static final Logger log = LoggerFactory.getLogger(DependencyValidator.class);

    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;

    public DependencyValidator(VmGroupRepository groupRepository, VmRepository vmRepository) {
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
    }

    /**
     * Validates that group dependencies:
     * 1. All referenced groups exist
     * 2. No circular dependencies
     * 3. All dependencies are in same environment
     */
    public void validateGroupDependencies(String environmentId, String newGroupId, List<String> dependsOnGroupIds) {
        if (dependsOnGroupIds == null || dependsOnGroupIds.isEmpty()) {
            return;
        }

        // Check all dependencies exist and are in same environment
        for (String depGroupId : dependsOnGroupIds) {
            VmGroup depGroup = groupRepository.findById(depGroupId)
                    .orElseThrow(() -> new ValidationException("Dependency group not found: " + depGroupId));

            if (!depGroup.getEnvironment().getEnvironmentId().equals(environmentId)) {
                throw new ValidationException("Dependency group must be in same environment");
            }
        }

        // Check for circular dependencies
        detectCircularDependency(environmentId, newGroupId, dependsOnGroupIds);
    }

    /**
     * Detect circular dependencies in group graph.
     */
    public void detectCircularDependency(String environmentId, String newGroupId, List<String> dependsOn) {
        // Build dependency graph
        Map<String, List<String>> graph = buildGroupDependencyGraph(environmentId);

        // Add new dependency edges
        graph.put(newGroupId, dependsOn != null ? new ArrayList<>(dependsOn) : new ArrayList<>());

        // Run cycle detection (DFS-based)
        if (hasCycle(graph)) {
            String cyclePath = findCyclePath(graph, newGroupId);
            throw new CircularDependencyException("Circular dependency detected: " + cyclePath);
        }
    }

    /**
     * Topological sort for determining start order.
     */
    public List<VmGroup> topologicalSort(List<VmGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Collections.emptyList();
        }

        // Build adjacency list
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, VmGroup> groupMap = new HashMap<>();

        for (VmGroup group : groups) {
            groupMap.put(group.getGroupId(), group);
            graph.putIfAbsent(group.getGroupId(), new ArrayList<>());
            inDegree.putIfAbsent(group.getGroupId(), 0);

            for (String depId : group.getDependencies()) {
                graph.putIfAbsent(depId, new ArrayList<>());
                graph.get(depId).add(group.getGroupId());
                inDegree.put(group.getGroupId(), inDegree.getOrDefault(group.getGroupId(), 0) + 1);
            }
        }

        // Kahn's algorithm for topological sort
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0 && groupMap.containsKey(entry.getKey())) {
                queue.offer(entry.getKey());
            }
        }

        List<VmGroup> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String groupId = queue.poll();
            if (groupMap.containsKey(groupId)) {
                sorted.add(groupMap.get(groupId));
            }

            for (String neighbor : graph.getOrDefault(groupId, Collections.emptyList())) {
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);
                if (inDegree.get(neighbor) == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        if (sorted.size() != groups.size()) {
            throw new CircularDependencyException("Circular dependency detected in group graph");
        }

        return sorted;
    }

    /**
     * Check if VM dependencies are satisfied (all deps in RUNNING state).
     */
    public boolean areVmDependenciesSatisfied(Vm vm) {
        if (vm.getDependencies().isEmpty()) {
            return true;
        }

        List<Vm> dependentVms = vmRepository.findAllById(vm.getDependencies());

        // All dependencies must be in RUNNING state
        return dependentVms.stream().allMatch(dep -> dep.getStatus() == VmStatus.RUNNING);
    }

    /**
     * Check if all VMs in dependency groups are running.
     */
    public boolean areGroupDependenciesSatisfied(VmGroup group) {
        if (group.getDependencies().isEmpty()) {
            return true;
        }

        for (String depGroupId : group.getDependencies()) {
            List<Vm> vmsInDepGroup = vmRepository.findByGroupId(depGroupId);

            // ALL VMs in dependent group must be RUNNING
            boolean allRunning = vmsInDepGroup.stream()
                    .allMatch(vm -> vm.getStatus() == VmStatus.RUNNING);

            if (!allRunning) {
                log.debug("Group {} dependency not satisfied: group {} has non-running VMs",
                        group.getGroupId(), depGroupId);
                return false;
            }
        }

        return true;
    }

    /**
     * Get VMs that can start in parallel (same sequence, all deps met).
     */
    public List<List<Vm>> getVmStartBatches(String groupId) {
        List<Vm> allVms = vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId);

        // Group by sequence position
        Map<Integer, List<Vm>> bySequence = allVms.stream()
                .collect(Collectors.groupingBy(Vm::getSequencePosition));

        // Sort by sequence and return as list of batches
        return bySequence.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Validates VM dependencies:
     * 1. All dependencies exist
     * 2. All dependencies are in SAME group
     * 3. No circular dependencies
     */
    public void validateVmDependencies(String groupId, String vmId, List<String> dependsOnVmIds) {
        if (dependsOnVmIds == null || dependsOnVmIds.isEmpty()) {
            return;
        }

        for (String depVmId : dependsOnVmIds) {
            Vm depVm = vmRepository.findById(depVmId)
                    .orElseThrow(() -> new ValidationException("Dependency VM not found: " + depVmId));

            // Verify dependency is in same group
            if (!depVm.getGroup().getGroupId().equals(groupId)) {
                throw new ValidationException("VM dependencies must be within the same group");
            }
        }

        // Check for circular dependencies
        detectVmCircularDependency(groupId, vmId, dependsOnVmIds);
    }

    // ============= Private Helper Methods =============

    private Map<String, List<String>> buildGroupDependencyGraph(String environmentId) {
        List<VmGroup> groups = groupRepository.findByEnvironmentId(environmentId);
        Map<String, List<String>> graph = new HashMap<>();

        for (VmGroup group : groups) {
            graph.put(group.getGroupId(), new ArrayList<>(group.getDependencies()));
        }

        return graph;
    }

    private void detectVmCircularDependency(String groupId, String newVmId, List<String> dependsOn) {
        List<Vm> vmsInGroup = vmRepository.findByGroupId(groupId);

        Map<String, List<String>> graph = new HashMap<>();
        for (Vm vm : vmsInGroup) {
            graph.put(vm.getVmId(), new ArrayList<>(vm.getDependencies()));
        }

        graph.put(newVmId, dependsOn != null ? new ArrayList<>(dependsOn) : new ArrayList<>());

        if (hasCycle(graph)) {
            throw new CircularDependencyException("Circular VM dependency detected in group " + groupId);
        }
    }

    private boolean hasCycle(Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String node : graph.keySet()) {
            if (hasCycleDFS(node, graph, visited, recursionStack)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasCycleDFS(String node, Map<String, List<String>> graph,
                                Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(node)) {
            return true; // Cycle detected
        }

        if (visited.contains(node)) {
            return false; // Already processed
        }

        visited.add(node);
        recursionStack.add(node);

        List<String> neighbors = graph.getOrDefault(node, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (hasCycleDFS(neighbor, graph, visited, recursionStack)) {
                return true;
            }
        }

        recursionStack.remove(node);
        return false;
    }

    private String findCyclePath(Map<String, List<String>> graph, String startNode) {
        Set<String> visited = new HashSet<>();
        List<String> path = new ArrayList<>();

        if (findCyclePathDFS(startNode, graph, visited, path)) {
            return String.join(" -> ", path);
        }

        return "Unknown cycle";
    }

    private boolean findCyclePathDFS(String node, Map<String, List<String>> graph,
                                     Set<String> visited, List<String> path) {
        if (path.contains(node)) {
            path.add(node);
            int cycleStart = path.indexOf(node);
            List<String> cyclePortion = new ArrayList<>(path.subList(cycleStart, path.size()));
            path.clear();
            path.addAll(cyclePortion);
            return true;
        }

        if (visited.contains(node)) {
            return false;
        }

        visited.add(node);
        path.add(node);

        List<String> neighbors = graph.getOrDefault(node, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (findCyclePathDFS(neighbor, graph, visited, path)) {
                return true;
            }
        }

        path.remove(path.size() - 1);
        return false;
    }
}

