package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服务端权威的编辑工程状态。契约见 {@code docs/protocol.md §7}。
 *
 * <p>生命周期：在 {@link moe.hikari.canvas.session.SessionManager#confirm} 里构造，
 * 随 {@link moe.hikari.canvas.session.Session} 一起 forget。</p>
 *
 * <p><b>并发与可变性：</b> 所有 mutator 均 package-private；外部只能经由
 * {@code EditSession}（M3-T6 引入）在 {@code SessionManager} 的 {@code synchronized}
 * 段内调用。读方法返回的 {@link #elements()} 为只读视图，不可从外部修改。</p>
 *
 * <p><b>Jackson 序列化：</b> 本类使用 {@code FIELD} 可见性把字段 {@code version / canvas
 * / elements / history} 直接映射到协议 §7 的 JSON 形态；{@code getter} 完全 skip。
 * 因此 {@link #version()} / {@link #canvas()} 等 Java-side 访问器的命名不影响 JSON。</p>
 */
@JsonAutoDetect(
        fieldVisibility = Visibility.ANY,
        getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE,
        setterVisibility = Visibility.NONE,
        creatorVisibility = Visibility.NONE
)
public final class ProjectState {

    public record Canvas(int widthMaps, int heightMaps, String background) {}

    public record History(int undoDepth, int redoDepth) {}

    private long version;
    private Canvas canvas;
    private final List<Element> elements;
    private History history;

    public ProjectState(int widthMaps, int heightMaps) {
        this(widthMaps, heightMaps, "#FFFFFF");
    }

    public ProjectState(int widthMaps, int heightMaps, String background) {
        this.version = 0;
        this.canvas = new Canvas(widthMaps, heightMaps, background);
        this.elements = new ArrayList<>();
        this.history = new History(0, 0);
    }

    // ---------- Java-side accessors（项目无前缀 getter 风格）----------

    public long version() { return version; }
    public Canvas canvas() { return canvas; }
    public List<Element> elements() { return Collections.unmodifiableList(elements); }
    public History history() { return history; }

    public int indexOfElement(String elementId) {
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).id().equals(elementId)) return i;
        }
        return -1;
    }

    // ---------- package-private mutators（M3-T6 EditSession 使用）----------

    /** 每次成功变更后递增 version；返回新版本号。 */
    public long bumpVersion() {
        return ++version;
    }

    public void canvas(Canvas c) {
        this.canvas = c;
    }

    public void history(History h) {
        this.history = h;
    }

    public void addElement(Element e) {
        elements.add(e);
    }

    public void addElement(int index, Element e) {
        elements.add(index, e);
    }

    public Element removeElementAt(int index) {
        return elements.remove(index);
    }

    public void replaceElementAt(int index, Element e) {
        elements.set(index, e);
    }

    /** 把 [from] 位置的元素移到 [to] 位置。reorder op 用。 */
    public void moveElement(int from, int to) {
        if (from == to) return;
        Element e = elements.remove(from);
        elements.add(to, e);
    }

    /**
     * 从 {@link ProjectSnapshot} 整体恢复 state（undo/redo 用）。
     * {@code version} 不回滚——恢复后调用方应 {@link #bumpVersion} 标记新版本。
     */
    public void restore(ProjectSnapshot snap) {
        this.canvas = snap.canvas();
        this.elements.clear();
        this.elements.addAll(snap.elements());
    }
}
