package dev.tethium.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.JTree;
import javax.swing.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** A project repeats only what no visible session row shows, for counters and dots alike. */
class ProjectRowFactsTest {
    private static WorkspaceFrame.Node row(String id, boolean project, String attention, int backlog) { return new WorkspaceFrame.Node(id, id, project, false, "", "", attention, false, 0, backlog); }
    @Test void aProjectShowsWhatItsVisibleRowsDoNot() {
        var root = new DefaultMutableTreeNode("root");
        var projectRow = row("p", true, "question", 4);
        DefaultMutableTreeNode project = new DefaultMutableTreeNode(projectRow), parent = new DefaultMutableTreeNode(row("a", false, "finished", 1)), child = new DefaultMutableTreeNode(row("b", false, "question", 2));
        root.add(project); project.add(parent); parent.add(child);
        var tree = new JTree(new DefaultTreeModel(root)); tree.setRootVisible(false);
        var projectPath = new TreePath(project.getPath()); var parentPath = new TreePath(parent.getPath());
        tree.collapsePath(parentPath); tree.collapsePath(projectPath);
        assertEquals(4, WorkspaceFrame.shownBacklog(tree, projectPath, projectRow), "collapsed: everything");
        assertEquals("question", WorkspaceFrame.shownAttention(tree, projectPath, projectRow));
        tree.expandPath(projectPath);
        assertEquals(3, WorkspaceFrame.shownBacklog(tree, projectPath, projectRow), "the parent's row is visible, its child is not");
        assertEquals("question", WorkspaceFrame.shownAttention(tree, projectPath, projectRow), "the child's dot cannot be seen");
        tree.expandPath(parentPath);
        assertEquals(1, WorkspaceFrame.shownBacklog(tree, projectPath, projectRow), "what is left belongs to no visible row, like a task for a new session");
        assertEquals("", WorkspaceFrame.shownAttention(tree, projectPath, projectRow), "every dot is on a visible row");
        var session = (WorkspaceFrame.Node) parent.getUserObject();
        assertEquals(1, WorkspaceFrame.shownBacklog(tree, parentPath, session), "a session always shows its own");
        assertEquals("finished", WorkspaceFrame.shownAttention(tree, parentPath, session));
    }
    @Test void aCollapsedProjectChoosesTheMostUrgentDot() {
        var root = new DefaultMutableTreeNode("root");
        var projectRow = row("p", true, "manual", 0);
        var project = new DefaultMutableTreeNode(projectRow);
        project.add(new DefaultMutableTreeNode(row("a", false, "finished", 0))); project.add(new DefaultMutableTreeNode(row("b", false, "manual", 0))); project.add(new DefaultMutableTreeNode(row("c", false, "finished", 0)));
        root.add(project);
        var tree = new JTree(new DefaultTreeModel(root)); tree.setRootVisible(false);
        tree.collapsePath(new TreePath(project.getPath()));
        assertEquals("manual", WorkspaceFrame.shownAttention(tree, new TreePath(project.getPath()), projectRow));
    }
}
