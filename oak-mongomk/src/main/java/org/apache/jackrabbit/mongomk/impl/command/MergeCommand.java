package org.apache.jackrabbit.mongomk.impl.command;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.mk.json.JsopBuilder;
import org.apache.jackrabbit.mk.model.tree.DiffBuilder;
import org.apache.jackrabbit.mk.model.tree.NodeState;
import org.apache.jackrabbit.mongomk.api.command.Command;
import org.apache.jackrabbit.mongomk.api.model.Commit;
import org.apache.jackrabbit.mongomk.api.model.Node;
import org.apache.jackrabbit.mongomk.impl.MongoNodeStore;
import org.apache.jackrabbit.mongomk.impl.action.FetchCommitAction;
import org.apache.jackrabbit.mongomk.impl.action.FetchHeadRevisionIdAction;
import org.apache.jackrabbit.mongomk.impl.json.DefaultJsopHandler;
import org.apache.jackrabbit.mongomk.impl.json.JsopParser;
import org.apache.jackrabbit.mongomk.impl.model.CommitBuilder;
import org.apache.jackrabbit.mongomk.impl.model.MongoCommit;
import org.apache.jackrabbit.mongomk.impl.model.NodeImpl;
import org.apache.jackrabbit.mongomk.impl.model.tree.MongoNodeDelta;
import org.apache.jackrabbit.mongomk.impl.model.tree.MongoNodeDelta.Conflict;
import org.apache.jackrabbit.mongomk.impl.model.tree.MongoNodeState;
import org.apache.jackrabbit.mongomk.impl.model.tree.SimpleMongoNodeStore;
import org.apache.jackrabbit.mongomk.util.MongoUtil;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@code Command} for {@code MongoMicroKernel#merge(String, String)}
 */
public class MergeCommand extends BaseCommand<String> {

    private static final Logger LOG = LoggerFactory.getLogger(MergeCommand.class);

    private final String branchRevisionId;
    private final String message;

    /**
     * Constructs a {@code MergeCommandMongo}
     *
     * @param nodeStore Node store.
     * @param branchRevisionId Branch revision id.
     * @param message Merge message.
     */
    public MergeCommand(MongoNodeStore nodeStore, String branchRevisionId,
            String message) {
        super(nodeStore);
        this.branchRevisionId = branchRevisionId;
        this.message = message;
    }

    @Override
    public String execute() throws Exception {
        MongoCommit commit = new FetchCommitAction(nodeStore,
                MongoUtil.toMongoRepresentation(branchRevisionId)).execute();
        String branchId = commit.getBranchId();
        if (branchId == null) {
            throw new Exception("Can only merge a private branch commit");
        }

        long rootNodeId = commit.getRevisionId();

        FetchHeadRevisionIdAction query2 = new FetchHeadRevisionIdAction(nodeStore);
        long currentHead = query2.execute();

        long branchRootId = Long.parseLong(branchId.substring(0, branchId.indexOf("-")));

        Commit newCommit;
        if (currentHead <= branchRootId) {
            // no commits to trunk since branch was created
            StringBuilder diff = new StringBuilder();
            for (long rev = branchRootId + 1; rev <= commit.getRevisionId(); rev++) {
                try {
                    Commit c = new FetchCommitAction(nodeStore, rev).execute();
                    if (branchId.equals(c.getBranchId())) {
                        diff.append(normalizeDiff(c.getPath(), c.getDiff()));
                    }
                } catch (Exception e) {
                    // commit does not exist
                }
            }
            newCommit = CommitBuilder.build("", diff.toString(),
                    MongoUtil.fromMongoRepresentation(currentHead), message);

        } else {
            Node ourRoot = getNode("/", rootNodeId, branchId);

            // Merge changes, if any, from trunk to branch.
            Node currentHeadNode = getNode("/", currentHead);
            if (currentHead != branchRootId) {
                ourRoot = mergeNodes(ourRoot, currentHeadNode, branchRootId);
            }

            String diff = new DiffBuilder(MongoUtil.wrap(currentHeadNode),
                    MongoUtil.wrap(ourRoot), "/", -1,
                    new SimpleMongoNodeStore(), "").build();

            if (diff.isEmpty()) {
                LOG.debug("Merge of empty branch {} with differing content hashes encountered, " +
                        "ignore and keep current head {}", branchRevisionId, currentHead);
                return MongoUtil.fromMongoRepresentation(currentHead);
            }

            newCommit = CommitBuilder.build("", diff,
                    MongoUtil.fromMongoRepresentation(currentHead), message);
        }

        Command<Long> command = new CommitCommandNew(nodeStore, newCommit);
        long revision = command.execute();
        return MongoUtil.fromMongoRepresentation(revision);
    }

    /**
     * Normalizes a JSOP diff by appending the path to all pathStrings of the
     * operations.
     *
     * @param path the root path of the diff.
     * @param diff the JSOP diff.
     * @return the JSOP diff based on an empty root path.
     */
    private String normalizeDiff(String path, String diff) throws Exception {
        NormalizingJsopHandler handler = new NormalizingJsopHandler();
        new JsopParser(path, diff, handler).parse();
        return handler.getDiff();
    }

    private NodeImpl mergeNodes(Node ourRoot, Node theirRoot,
            Long commonAncestorRevisionId) throws Exception {

        Node baseRoot = getNode("/", commonAncestorRevisionId);
        Node theirRootCopy = copy(theirRoot);

        // Recursively merge 'our' changes with 'their' changes...
        return mergeNode(baseRoot, ourRoot, theirRootCopy, "/");
    }

    private NodeImpl mergeNode(Node baseNode, Node ourNode, Node theirNode,
            String path) throws Exception {
        MongoNodeDelta theirChanges = new MongoNodeDelta(new SimpleMongoNodeStore(),
                MongoUtil.wrap(baseNode), MongoUtil.wrap(theirNode));
        MongoNodeDelta ourChanges = new MongoNodeDelta(new SimpleMongoNodeStore(),
                MongoUtil.wrap(baseNode), MongoUtil.wrap(ourNode));

        NodeImpl stagedNode = (NodeImpl)theirNode; //new NodeImpl(path);

        // Apply our changes.
        stagedNode.getProperties().putAll(ourChanges.getAddedProperties());
        stagedNode.getProperties().putAll(ourChanges.getChangedProperties());
        for (String name : ourChanges.getRemovedProperties().keySet()) {
            stagedNode.getProperties().remove(name);
        }

        for (Map.Entry<String, NodeState> entry : ourChanges.getAddedChildNodes().entrySet()) {
            MongoNodeState nodeState = (MongoNodeState)entry.getValue();
            stagedNode.addChildNodeEntry(nodeState.unwrap());
        }
        for (Map.Entry<String, NodeState> entry : ourChanges.getChangedChildNodes().entrySet()) {
            if (!theirChanges.getChangedChildNodes().containsKey(entry.getKey())) {
                MongoNodeState nodeState = (MongoNodeState)entry.getValue();
                stagedNode.addChildNodeEntry(nodeState.unwrap());
            }
        }
        for (String name : ourChanges.getRemovedChildNodes().keySet()) {
            stagedNode.removeChildNodeEntry(name);
        }

        List<Conflict> conflicts = theirChanges.listConflicts(ourChanges);
        // resolve/report merge conflicts
        for (Conflict conflict : conflicts) {
            String conflictName = conflict.getName();
            String conflictPath = PathUtils.concat(path, conflictName);
            switch (conflict.getType()) {
                case PROPERTY_VALUE_CONFLICT:
                    throw new Exception(
                            "concurrent modification of property " + conflictPath
                                    + " with conflicting values: \""
                                    + ourNode.getProperties().get(conflictName)
                                    + "\", \""
                                    + theirNode.getProperties().get(conflictName));

                case NODE_CONTENT_CONFLICT: {
                    if (ourChanges.getChangedChildNodes().containsKey(conflictName)) {
                        // modified subtrees
                        Node baseChild = baseNode.getChildNodeEntry(conflictName);
                        Node ourChild = ourNode.getChildNodeEntry(conflictName);
                        Node theirChild = theirNode.getChildNodeEntry(conflictName);
                        // merge the dirty subtrees recursively
                        mergeNode(baseChild, ourChild, theirChild, PathUtils.concat(path, conflictName));
                    } else {
                        // todo handle/merge colliding node creation
                        throw new Exception("colliding concurrent node creation: " + conflictPath);
                    }
                    break;
                }

                case REMOVED_DIRTY_PROPERTY_CONFLICT:
                    stagedNode.getProperties().remove(conflictName);
                    break;

                case REMOVED_DIRTY_NODE_CONFLICT:
                    //stagedNode.remove(conflictName);
                    stagedNode.removeChildNodeEntry(conflictName);
                    break;
            }

        }
        return stagedNode;
    }

    private Node getNode(String path, long revisionId) throws Exception {
        return getNode(path, revisionId, null);
    }

    private Node getNode(String path, long revisionId, String branchId) throws Exception {
        GetNodesCommandNew command = new GetNodesCommandNew(nodeStore, path, revisionId);
        command.setBranchId(branchId);
        return command.execute();
    }

    private NodeImpl copy(Node node) {
        NodeImpl copy = new NodeImpl(node.getPath());
        copy.setRevisionId(node.getRevisionId());
        for (Map.Entry<String, String> entry : node.getProperties().entrySet()) {
            copy.addProperty(entry.getKey(), entry.getValue());
        }
        for (Iterator<Node> it = node.getChildNodeEntries(0, -1); it.hasNext(); ) {
            Node child = it.next();
            copy.addChildNodeEntry(copy(child));
        }
        return copy;
    }

    private static class NormalizingJsopHandler extends DefaultJsopHandler {

        private final StringBuilder builder = new StringBuilder();

        @Override
        public void nodeAdded(String parentPath, String name) {
            builder.append("+");
            builder.append(JsopBuilder.encode(concatPath(parentPath, name)));
            builder.append(":{}");
        }

        @Override
        public void nodeCopied(String rootPath,
                               String oldPath,
                               String newPath) {
            builder.append("*");
            builder.append(JsopBuilder.encode(concatPath(rootPath, oldPath)));
            builder.append(":");
            builder.append(JsopBuilder.encode(concatPath(rootPath, newPath)));
        }

        @Override
        public void nodeMoved(String rootPath, String oldPath, String newPath) {
            builder.append(">");
            builder.append(JsopBuilder.encode(oldPath));
            builder.append(":");
            builder.append(JsopBuilder.encode(newPath));
        }

        @Override
        public void nodeRemoved(String parentPath, String name) {
            builder.append("-");
            builder.append(JsopBuilder.encode(concatPath(parentPath, name)));
        }

        @Override
        public void propertySet(String path, String key, Object value, String rawValue) {
            builder.append("^");
            builder.append(JsopBuilder.encode(concatPath(path, key)));
            builder.append(":");
            builder.append(rawValue);
        }

        private String concatPath(String parent, String child) {
            if (parent.length() == 0) {
                return child;
            } else {
                return PathUtils.concat(parent, child);
            }
        }

        String getDiff() {
            return builder.toString();
        }
    }
}