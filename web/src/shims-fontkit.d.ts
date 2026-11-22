declare module 'fontkit' {
    export interface FontkitPath {
        commands: Array<{ command: string; args: number[] }>;
        toSVG(): string;
    }

    export interface FontkitGlyph {
        path: FontkitPath;
        advanceWidth: number;
    }

    export interface FontkitFont {
        unitsPerEm: number;
        glyphForCodePoint(codePoint: number): FontkitGlyph;
    }

    export function create(buffer: Uint8Array): FontkitFont;
}
