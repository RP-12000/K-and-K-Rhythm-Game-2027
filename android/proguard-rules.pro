# To enable ProGuard in your project, edit project.properties
# to define the proguard.config property as described in that file.
#
# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the ProGuard
# include property in project.properties.
#
# For more details, see
#   https://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-verbose

-dontwarn android.support.**
-dontwarn com.badlogic.gdx.backends.android.AndroidFragmentApplication

# BitmapFont and Color (libGDX template rules). The template's Box2D, controllers and scene2d rules are
# gone: the game uses none of them (only the static scene2d ScissorStack helper, which needs no rule).
-keep public class com.badlogic.gdx.graphics.g2d.BitmapFont { *; }
-keep public class com.badlogic.gdx.graphics.Color { *; }

# These two lines are used with mapping files; see https://developer.android.com/build/shrink-code#retracing
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

# ---- K & K: JSON ----
# Charts, chart headers and the profile files are read with Jackson's streaming core (no databind).
-keep class com.fasterxml.jackson.core.** { *; }

# profile/PlainJson binds the profile model's public fields by name through reflection: keep the
# names, the no-argument constructors and the generic signatures (List<T>, Map<String, V>).
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class org.kelvinizer.profile.** { *; }
-keep class org.kelvinizer.elements.** { *; }
-keep class org.kelvinizer.library.** { *; }
-keep class org.kelvinizer.play.** { *; }
-keepclassmembers enum org.kelvinizer.** { *; }
