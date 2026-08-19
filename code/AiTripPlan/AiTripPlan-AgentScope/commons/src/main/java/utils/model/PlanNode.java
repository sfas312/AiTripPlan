package utils.model;

import java.util.*;

/**
 * Represents a node in the trip plan graph.
 * Each node corresponds to a specific planning entity: a day, route leg,
 * activity, accommodation, or meal.
 *
 * Supports both tree (DayNode contains children) and graph (edges between nodes)
 * representations.
 */
public class PlanNode implements Comparable<PlanNode> {

    /** Node type enum */
    public enum NodeType {
        ROOT,           // root of plan
        DAY,            // a single day
        ROUTE,          // a driving route segment
        ACTIVITY,       // a scenic spot or activity
        ACCOMMODATION,  // hotel / lodging
        MEAL            // dining
    }

    private final String id;
    private final NodeType type;
    private String label;             // human-readable name (e.g. "Day 1", "广州→厦门")
    private String content;           // description or LLM-generated text for this node

    // Hierarchical structure
    private PlanNode parent;
    private final List<PlanNode> children = new ArrayList<>();

    // Graph edges: dependency / constraint edges to other nodes
    private final List<String> edgeTargets = new ArrayList<>();
    private final Map<String, EdgeType> edgeTypes = new LinkedHashMap<>();

    // Cost attributes (used by budget constraint analysis)
    private double estimatedCost = 0.0;      // 元
    private double estimatedDuration = 0.0;  // 小时

    // Metadata for constraint impact analysis
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    // ========== Constructors ==========

    public PlanNode(String id, NodeType type) {
        this.id = id;
        this.type = type;
    }

    public PlanNode(String id, NodeType type, String label) {
        this.id = id;
        this.type = type;
        this.label = label;
    }

    // ========== Tree operations ==========

    public PlanNode addChild(PlanNode child) {
        children.add(child);
        child.setParent(this);
        return this;
    }

    public PlanNode removeChild(PlanNode child) {
        children.remove(child);
        child.setParent(null);
        return this;
    }

    /** Returns all descendants (children, grandchildren, etc.) in DFS order */
    public List<PlanNode> getAllDescendants() {
        List<PlanNode> result = new ArrayList<>();
        for (PlanNode child : children) {
            result.add(child);
            result.addAll(child.getAllDescendants());
        }
        return result;
    }

    /** Returns all ancestors (parent, grandparent, ... root) in order */
    public List<PlanNode> getAncestors() {
        List<PlanNode> result = new ArrayList<>();
        PlanNode current = parent;
        while (current != null) {
            result.add(current);
            current = current.getParent();
        }
        return result;
    }

    /** Returns siblings of this node */
    public List<PlanNode> getSiblings() {
        if (parent == null) return Collections.emptyList();
        List<PlanNode> sibs = new ArrayList<>(parent.getChildren());
        sibs.remove(this);
        return sibs;
    }

    /** Returns the root node by walking up the tree */
    public PlanNode getRoot() {
        PlanNode current = this;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current;
    }

    // ========== Graph edge operations ==========

    /** Adds a cross-edge to another node (non-hierarchical dependency) */
    public PlanNode addEdge(PlanNode target, EdgeType edgeType) {
        edgeTargets.add(target.getId());
        edgeTypes.put(target.getId(), edgeType);
        return this;
    }

    /** Returns all nodes this node depends on (edges pointing FROM this node) */
    public List<String> getDependencyIds() {
        return Collections.unmodifiableList(edgeTargets);
    }

    public EdgeType getEdgeType(String targetId) {
        return edgeTypes.getOrDefault(targetId, EdgeType.TEMPORAL);
    }

    // ========== Constraint-related helpers ==========

    public boolean hasCost() {
        return estimatedCost > 0;
    }

    public boolean isDayNode() {
        return type == NodeType.DAY;
    }

    public boolean isRouteNode() {
        return type == NodeType.ROUTE;
    }

    /** Checks if this node is within a given day index range (inclusive) */
    public boolean isInDayRange(int startDay, int endDay) {
        if (type == NodeType.DAY) {
            String dayNumStr = label.replaceAll("[^0-9]", "");
            try {
                int dayNum = Integer.parseInt(dayNumStr);
                return dayNum >= startDay && dayNum <= endDay;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // For child nodes, check parent day
        if (parent != null && parent.getType() == NodeType.DAY) {
            return parent.isInDayRange(startDay, endDay);
        }
        return false;
    }

    // ========== Getters and Setters ==========

    public String getId() { return id; }
    public NodeType getType() { return type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public PlanNode getParent() { return parent; }
    public void setParent(PlanNode parent) { this.parent = parent; }
    public List<PlanNode> getChildren() { return Collections.unmodifiableList(children); }
    public double getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(double cost) { this.estimatedCost = cost; }
    public double getEstimatedDuration() { return estimatedDuration; }
    public void setEstimatedDuration(double duration) { this.estimatedDuration = duration; }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setMetadata(String key, Object value) { metadata.put(key, value); }
    public Object getMetadata(String key) { return metadata.get(key); }

    // ========== Edge type ==========

    public enum EdgeType {
        TEMPORAL,    // sequential dependency (before/after)
        CAUSAL,      // causal dependency (e.g., route determines day-1 start point)
        BUDGET,      // budget allocation
        SPATIAL      // spatial proximity constraint
    }

    // ========== Object methods ==========

    @Override
    public String toString() {
        return "PlanNode{id='" + id + "', type=" + type + ", label='" + label + "'"
                + (estimatedCost > 0 ? ", cost=" + estimatedCost : "")
                + ", children=" + children.size() + ", edges=" + edgeTargets.size() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlanNode other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public int compareTo(PlanNode o) { return this.id.compareTo(o.id); }
}
