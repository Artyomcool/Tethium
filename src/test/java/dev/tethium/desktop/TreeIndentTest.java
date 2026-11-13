package dev.tethium.desktop;

import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TreeIndentTest {
    @Test void keepsTheFirstLevelGutterButCompressesFollowingLevels() {
        var root=new DefaultMutableTreeNode("root");
        var first=new DefaultMutableTreeNode("first");
        var second=new DefaultMutableTreeNode("second");
        var third=new DefaultMutableTreeNode("third");
        root.add(first);first.add(second);second.add(third);
        var tree=new JTree(root);tree.setRootVisible(false);tree.setShowsRootHandles(true);
        WorkspaceFrame.configureTreeIndent(tree);
        tree.expandPath(new TreePath(first.getPath()));tree.expandPath(new TreePath(second.getPath()));

        assertEquals(24,tree.getRowBounds(0).x,"top-level row retains the old left gutter");
        assertEquals(5,tree.getRowBounds(1).x-tree.getRowBounds(0).x,"one compact level");
        assertEquals(5,tree.getRowBounds(2).x-tree.getRowBounds(1).x,"every level uses the same compact step");
    }

    @Test void logicalSelectionPaintsToTheLastViewportPixel() {
        var root=new DefaultMutableTreeNode("root");
        root.add(new DefaultMutableTreeNode(new WorkspaceFrame.Node("session","Session",false,false,"","","",false,0,0)));
        var tree=new JTree(root);tree.setRootVisible(false);tree.setRowHeight(28);tree.setSize(240,40);
        WorkspaceFrame.configureTreeIndent(tree);
        var image=new BufferedImage(240,40,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        WorkspaceFrame.paintTreeBackground(tree,graphics,"session",null,null);graphics.dispose();
        var row=tree.getRowBounds(0);
        assertEquals(Theme.SELECTED.getRGB(),image.getRGB(239,row.y+row.height/2),"selection reaches the viewport edge");

        image=new BufferedImage(240,40,BufferedImage.TYPE_INT_RGB);graphics=image.createGraphics();
        WorkspaceFrame.paintTreeBackground(tree,graphics,"another",null,null);graphics.dispose();
        assertEquals(Theme.SIDEBAR.getRGB(),image.getRGB(239,row.y+row.height/2),"an unrelated row stays unselected");
    }
}
