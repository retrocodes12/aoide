@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.aoide.debug

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import app.aoide.player.PlayerController
import app.aoide.player.StreamResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * `adb shell am broadcast -a app.aoide.DEBUG --es cmd dump`   -> files/ui.json (semantics tree)
 * `... --es cmd tap --es tag play_fab [--ei n 0]`            -> performs the click action on the n-th node with that tag
 * `... --es cmd tapdesc --es text "Pause"`                    -> click by content description (contains)
 * `... --es cmd type --es tag search_field --es text "radiohead"`
 * `... --es cmd state`                                        -> files/state.json (player state)
 * Results are read with `adb shell run-as app.aoide.debug cat files/ui.json`.
 */
class Inspector : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).post {
            try {
                val out = when (cmd) {
                    "dump" -> dump(context)
                    "tap" -> tap(context, intent.getStringExtra("tag") ?: "", intent.getIntExtra("n", 0))
                    "tapdesc" -> tapDesc(context, intent.getStringExtra("text") ?: "", intent.getIntExtra("n", 0))
                    "type" -> type(context, intent.getStringExtra("tag") ?: "", intent.getStringExtra("text") ?: "")
                    "state" -> state(context)
                    else -> "unknown cmd"
                }
                File(context.filesDir, "result.txt").writeText(out)
                android.util.Log.i("AoideInspector", "$cmd -> $out")
            } catch (e: Throwable) {
                File(context.filesDir, "result.txt").writeText("error: $e")
                android.util.Log.e("AoideInspector", "failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun roots(): List<SemanticsNode> {
        val act = Tracker.activity?.get() ?: return emptyList()
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        val views = ArrayList<View>()
        fun walk(v: View) {
            if (v is RootForTest) views.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(content)
        // Dialogs / bottom sheets live in their own windows; grab them through the activity's window manager is not
        // possible from here, so we also include any ComposeView reachable from the decor view.
        walk(act.window.decorView)
        return views.distinct().map { (it as RootForTest).semanticsOwner.rootSemanticsNode }
    }

    private fun allNodes(): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun rec(n: SemanticsNode) {
            out.add(n)
            n.children.forEach(::rec)
        }
        roots().forEach(::rec)
        // Newer windows (bottom sheets) are appended last; prefer them for taps by reversing when searching.
        return out
    }

    private fun describe(n: SemanticsNode): JSONObject {
        val c = n.config
        val o = JSONObject()
        c.getOrNull(SemanticsProperties.TestTag)?.let { o.put("tag", it) }
        c.getOrNull(SemanticsProperties.Text)?.let { o.put("text", it.joinToString(" | ") { t: AnnotatedString -> t.text }) }
        c.getOrNull(SemanticsProperties.EditableText)?.let { o.put("edit", it.text) }
        c.getOrNull(SemanticsProperties.ContentDescription)?.let { o.put("desc", it.joinToString(" | ")) }
        c.getOrNull(SemanticsProperties.Role)?.let { o.put("role", it.toString()) }
        c.getOrNull(SemanticsProperties.Selected)?.let { o.put("selected", it) }
        if (c.contains(SemanticsActions.OnClick)) o.put("clickable", true)
        val b = n.boundsInWindow
        o.put("bounds", "[${b.left.toInt()},${b.top.toInt()}][${b.right.toInt()},${b.bottom.toInt()}]")
        return o
    }

    private fun dump(context: Context): String {
        val arr = JSONArray()
        for (n in allNodes()) {
            val d = describe(n)
            if (d.has("tag") || d.has("text") || d.has("desc") || d.has("edit")) arr.put(d)
        }
        File(context.filesDir, "ui.json").writeText(arr.toString(1))
        return "dumped ${arr.length()} nodes"
    }

    private fun clickNode(n: SemanticsNode): Boolean {
        var cur: SemanticsNode? = n
        while (cur != null) {
            val a = cur.config.getOrNull(SemanticsActions.OnClick)
            if (a != null) return a.action?.invoke() ?: false
            cur = cur.parent
        }
        return false
    }

    private fun tap(context: Context, tag: String, n: Int): String {
        val matches = allNodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        val node = matches.getOrNull(n) ?: return "no node with tag=$tag n=$n (found ${matches.size})"
        return if (clickNode(node)) "tapped $tag#$n" else "node $tag#$n has no click action"
    }

    private fun tapDesc(context: Context, text: String, n: Int): String {
        val matches = allNodes().filter { node ->
            (node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text, true) } == true) ||
                (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text, true) } == true)
        }
        val node = matches.getOrNull(n) ?: return "no node containing '$text' (found ${matches.size})"
        return if (clickNode(node)) "tapped '$text'#$n" else "node '$text' has no click action"
    }

    private fun type(context: Context, tag: String, text: String): String {
        val node = allNodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: return "no node with tag=$tag"
        node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        val set = node.config.getOrNull(SemanticsActions.SetText) ?: return "node $tag is not editable"
        return if (set.action?.invoke(AnnotatedString(text)) == true) "typed into $tag" else "set text failed"
    }

    private fun state(context: Context): String {
        val s = PlayerController.state.value
        val cur = s.current
        val o = JSONObject()
        o.put("status", s.status.name)
        o.put("index", s.index)
        o.put("queue", s.queue.size)
        o.put("positionMs", s.positionMs)
        o.put("durationMs", s.durationMs)
        o.put("shuffle", s.shuffle)
        o.put("repeat", s.repeat)
        o.put("title", cur?.title)
        o.put("artist", cur?.artistNames)
        o.put("context", s.context?.title)
        o.put("error", s.error)
        cur?.let { StreamResolver.infoFor(it.id) }?.let { o.put("quality", it.label); o.put("source", it.source) }
        o.put("upcoming", JSONArray(s.upcoming.map { it.title }))
        val txt = o.toString(1)
        File(context.filesDir, "state.json").writeText(txt)
        return txt
    }

    /** Remembers the foreground activity so the receiver can reach its Compose roots. */
    object Tracker : Application.ActivityLifecycleCallbacks {
        var activity: WeakReference<Activity>? = null
        override fun onActivityResumed(a: Activity) { activity = WeakReference(a) }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
