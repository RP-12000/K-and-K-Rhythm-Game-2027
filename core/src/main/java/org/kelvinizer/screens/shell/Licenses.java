package org.kelvinizer.screens.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;

/**
 * 「开源许可证」页的数据：每个字体和随游戏打包的软件库，各自的版权行、许可证名，
 * 以及点「查看」时显示的全文文件。全文放在 {@code assets/licenses/}，字体的 OFL 全文在
 * {@code assets/fonts/}；许可证原文保持英文。
 *
 * <p>The credits of the open-source licences page (Kelvin 2026-10-18 00:24): every typeface and every
 * library packed into the game, with its copyright notice, the licence name and the text files VIEW
 * shows ({@code assets/licenses/}; the OFL texts are the ones next to the fonts in
 * {@code assets/fonts/}). The legal texts stay in English in both languages. {@link #DESKTOP}
 * entries ship only in the computer build (LWJGL and its natives, the MP3 and OGG decoders of the
 * libGDX backend), {@link #ANDROID} only in the APK (the Java library desugaring). Keep this list
 * in step with the Gradle dependencies.
 */
public final class Licenses {
    /** Where an entry ships. */
    public static final int BOTH = 0, DESKTOP = 1, ANDROID = 2;

    /** One credit: name, copyright notice, licence name, platform and its text files. */
    public static final class Entry {
        /** Display name (a proper name, never translated). */
        public final String name;
        /** Copyright notice line(s), as the licence asks them to be reproduced. */
        public final String notice;
        /** Licence name. */
        public final String licence;
        /** {@link #BOTH}, {@link #DESKTOP} or {@link #ANDROID}. */
        public final int platform;
        /** Internal asset paths of the texts VIEW shows, in order. */
        public final String[] files;

        Entry(String name, String notice, String licence, int platform, String... files) {
            this.name = name;
            this.notice = notice;
            this.licence = licence;
            this.platform = platform;
            this.files = files;
        }
    }

    private static final String OFL = "SIL OPEN FONT LICENSE 1.1";
    private static final String APACHE = "APACHE LICENSE 2.0";

    /** The game's own code and documentation (root {@code LICENSE}). */
    public static final Entry GAME = new Entry("K & K", "Copyright (c) 2026 The K & K Rhythm Game authors",
        "MIT LICENSE", BOTH, "licenses/MIT.txt");

    /** Typefaces in {@code assets/fonts/}. */
    public static final Entry[] TYPEFACES = {
        new Entry("CHAKRA PETCH", "Copyright 2018 The Chakra Petch Project Authors", OFL, BOTH,
            "fonts/OFL-ChakraPetch.txt"),
        new Entry("NOTO SANS SC", "Copyright 2014-2021 Adobe, with Reserved Font Name 'Source'", OFL, BOTH,
            "fonts/OFL-NotoSansSC.txt"),
        new Entry("RUBIK GLITCH", "Copyright 2020 The Rubik Filtered Project Authors", OFL, BOTH,
            "fonts/OFL-RubikGlitch.txt"),
    };

    /** Libraries packed into the builds. */
    public static final Entry[] SOFTWARE = {
        new Entry("LIBGDX", "Copyright 2011 The libGDX authors", APACHE, BOTH, "licenses/Apache-2.0.txt"),
        new Entry("FREETYPE", "Portions of this software are copyright © 2024 The FreeType Project "
            + "(www.freetype.org). All rights reserved.", "FREETYPE LICENSE", BOTH, "licenses/FTL.txt"),
        new Entry("JACKSON CORE", "Copyright 2007- Tatu Saloranta", APACHE, BOTH,
            "licenses/Jackson-core-NOTICE.txt", "licenses/Apache-2.0.txt"),
        new Entry("SHAPEDRAWER", "Copyright (c) 2019 earlygrey", "MIT LICENSE", BOTH, "licenses/MIT.txt"),
        new Entry("LWJGL 3", "Copyright (c) 2012-present Lightweight Java Game Library", "BSD 3-CLAUSE", DESKTOP,
            "licenses/LWJGL-BSD-3-Clause.txt"),
        new Entry("GLFW", "Copyright (c) 2002-2006 Marcus Geelnard\nCopyright (c) 2006-2019 Camilla Löwy",
            "ZLIB LICENSE", DESKTOP, "licenses/GLFW-Zlib.txt"),
        new Entry("OPENAL SOFT", "Copyright (C) 1999-2007 by authors\nSource code: https://openal-soft.org",
            "GNU LGPL 2.0", DESKTOP, "licenses/LGPL-2.0.txt"),
        new Entry("JEMALLOC", "Copyright (C) 2002-present Jason Evans\nCopyright (C) 2007-2012 Mozilla Foundation"
            + "\nCopyright (C) 2009-present Facebook, Inc.", "BSD 2-CLAUSE", DESKTOP,
            "licenses/jemalloc-BSD-2-Clause.txt"),
        new Entry("STB", "Copyright (c) 2017 Sean Barrett", "MIT OR PUBLIC DOMAIN", DESKTOP,
            "licenses/stb-MIT-or-Unlicense.txt"),
        new Entry("JLAYER", "JavaZOOM (www.javazoom.net), libGDX fork\n"
            + "Source code: https://github.com/libgdx/jlayer-gdx",
            "GNU LGPL 2.1", DESKTOP, "licenses/LGPL-2.1.txt"),
        new Entry("JORBIS", "Copyright (C) 2000 ymnk, JCraft, Inc.\nSource code: http://www.jcraft.com/jorbis/",
            "GNU LGPL 2.0", DESKTOP, "licenses/LGPL-2.0.txt"),
        new Entry("DESUGAR JDK LIBS", "Copyright (c) Oracle and/or its affiliates; Google LLC\n"
            + "Source code: https://github.com/google/desugar_jdk_libs", "GPL 2.0 WITH CLASSPATH EXCEPTION", ANDROID,
            "licenses/GPL-2.0-with-Classpath-exception.txt"),
    };

    private Licenses() {
    }

    /**
     * The text VIEW shows for {@code e}: the notice, a blank line, then each file (a blank line
     * between files). Read when the page opens, not per frame; a missing file shows one line
     * saying so.
     */
    public static String[] lines(Entry e) {
        ArrayList<String> out = new ArrayList<>();
        for (String l : e.notice.split("\n")) out.add(l);
        for (String f : e.files) {
            out.add("");
            FileHandle fh = Gdx.files != null ? Gdx.files.internal(f) : null;
            if (fh == null || !fh.exists()) {
                out.add("(" + f + " is missing)");
                continue;
            }
            String text = fh.readString("UTF-8").replace("\r", "").replace("\t", "    ");
            for (String l : text.split("\n", -1)) out.add(l);
            while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        }
        return out.toArray(new String[0]);
    }
}
