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
 * {@code assets/fonts/}). The legal texts stay in English in both languages. The game ships only
 * as the Android APK, so this is the APK's list (it includes the Java library desugaring). Keep
 * this list in step with the Gradle dependencies.
 */
public final class Licenses {
    /** One credit: name, copyright notice, licence name and its text files. */
    public static final class Entry {
        /** Display name (a proper name, never translated). */
        public final String name;
        /** Copyright notice line(s), as the licence asks them to be reproduced. */
        public final String notice;
        /** Licence name. */
        public final String licence;
        /** Internal asset paths of the texts VIEW shows, in order. */
        public final String[] files;

        Entry(String name, String notice, String licence, String... files) {
            this.name = name;
            this.notice = notice;
            this.licence = licence;
            this.files = files;
        }
    }

    private static final String OFL = "SIL OPEN FONT LICENSE 1.1";
    private static final String APACHE = "APACHE LICENSE 2.0";

    /** The game's own code and documentation (root {@code LICENSE}). */
    public static final Entry GAME = new Entry("K & K", "Copyright (c) 2026 The K & K Rhythm Game authors",
        "MIT LICENSE", "licenses/MIT.txt");

    /** Typefaces in {@code assets/fonts/}. */
    public static final Entry[] TYPEFACES = {
        new Entry("CHAKRA PETCH", "Copyright 2018 The Chakra Petch Project Authors", OFL,
            "fonts/OFL-ChakraPetch.txt"),
        new Entry("NOTO SANS SC", "Copyright 2014-2021 Adobe, with Reserved Font Name 'Source'", OFL,
            "fonts/OFL-NotoSansSC.txt"),
        new Entry("RUBIK GLITCH", "Copyright 2020 The Rubik Filtered Project Authors", OFL,
            "fonts/OFL-RubikGlitch.txt"),
    };

    /** Libraries packed into the builds. */
    public static final Entry[] SOFTWARE = {
        new Entry("LIBGDX", "Copyright 2011 The libGDX authors", APACHE, "licenses/Apache-2.0.txt"),
        new Entry("FREETYPE", "Portions of this software are copyright © 2024 The FreeType Project "
            + "(www.freetype.org). All rights reserved.", "FREETYPE LICENSE", "licenses/FTL.txt"),
        new Entry("JACKSON CORE", "Copyright 2007- Tatu Saloranta", APACHE,
            "licenses/Jackson-core-NOTICE.txt", "licenses/Apache-2.0.txt"),
        new Entry("SHAPEDRAWER", "Copyright (c) 2019 earlygrey", "MIT LICENSE", "licenses/MIT.txt"),
        new Entry("DESUGAR JDK LIBS", "Copyright (c) Oracle and/or its affiliates; Google LLC\n"
            + "Source code: https://github.com/google/desugar_jdk_libs", "GPL 2.0 WITH CLASSPATH EXCEPTION",
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
