package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathFillRuleTest {

    @Test
    void parseFillRule_acceptsNonzeroEvenoddNull() {
        assertEquals("nonzero", ElementValidator.parseFillRuleNullable("nonzero"));
        assertEquals("evenodd", ElementValidator.parseFillRuleNullable("evenodd"));
        assertNull(ElementValidator.parseFillRuleNullable(null));
    }

    @Test
    void parseFillRule_rejectsGarbage() {
        assertThrows(RuntimeException.class, () -> ElementValidator.parseFillRuleNullable("winding"));
    }

    @Test
    void pathElement_carriesFillRule() {
        // PathElement record field order (16 fields + new fillRule = 17th):
        // id, x, y, w, h, rotation, locked, visible, d, fill, stroke,
        // markerStart, markerEnd, opacity, blendMode, renderMode, fillRule
        PathElement p = new PathElement(
                "test-id",          // id
                0, 0, 64, 64,       // x, y, w, h
                0,                  // rotation
                false,              // locked
                true,               // visible
                "M0 0 L10 10",      // d
                Fill.solid("#FF0000"), // fill
                null,               // stroke
                null,               // markerStart
                null,               // markerEnd
                null,               // opacity
                null,               // blendMode
                null,               // renderMode
                "evenodd"           // fillRule (new 17th field)
        );
        assertEquals("evenodd", p.fillRule());
    }
}
