package dev.tvlauncher.home

import android.content.Context
import org.json.JSONArray

/**
 * The "New" sky layout, exported from the approved mockup (assets/sky_new.json): three depth classes of
 * galaxies (small and slow, medium, large and quick), each with its own nebula glows, star boxes and knots.
 */
object SkyNew {
    /** A rotated box of tiny stars (kind 0) and glowing knots (kind 1) that sits inside a galaxy. */
    class Box(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float,
              val kind: IntArray, val v: FloatArray, val col: IntArray, val stop: FloatArray)
    class TileData(val neb: List<SkyData.Neb>, val gal: List<SkyData.Gal>, val box: List<Box>)
    class Cls(val speed: Float, val tiles: Array<TileData>)

    private var cached: Array<Cls>? = null

    @Synchronized
    fun get(ctx: Context): Array<Cls> {
        cached?.let { return it }
        val txt = ctx.assets.open("sky_new.json").bufferedReader().use { it.readText() }
        val root = JSONArray(txt)
        val out = Array(root.length()) { ci ->
            val c = root.getJSONObject(ci)
            val tiles = c.getJSONArray("tiles")
            Cls(c.getDouble("speed").toFloat(), Array(tiles.length()) { ti ->
                val t = tiles.getJSONObject(ti)
                val nebs = t.getJSONArray("neb"); val gals = t.getJSONArray("gal"); val boxes = t.getJSONArray("box")
                TileData(
                    List(nebs.length()) { i ->
                        val a = nebs.getJSONArray(i)
                        SkyData.Neb(f(a, 0), f(a, 1), f(a, 2), f(a, 3), a.getLong(4).toInt(), f(a, 5), f(a, 6), f(a, 7))
                    },
                    List(gals.length()) { i ->
                        val a = gals.getJSONArray(i)
                        val p = a.getJSONArray(5); val cl = a.getJSONArray(6)
                        SkyData.Gal(f(a, 0), f(a, 1), f(a, 2), f(a, 3), f(a, 4),
                            FloatArray(p.length()) { p.getDouble(it).toFloat() }, IntArray(cl.length()) { cl.getLong(it).toInt() })
                    },
                    List(boxes.length()) { i ->
                        val a = boxes.getJSONArray(i)
                        val kids = a.getJSONArray(5)
                        val n = kids.length()
                        val kind = IntArray(n); val v = FloatArray(n * 3); val col = IntArray(n); val stop = FloatArray(n)
                        for (j in 0 until n) {
                            val k = kids.getJSONArray(j)
                            kind[j] = k.getInt(0)
                            v[j * 3] = f(k, 1); v[j * 3 + 1] = f(k, 2); v[j * 3 + 2] = f(k, 3)
                            col[j] = k.getLong(4).toInt(); stop[j] = f(k, 5)
                        }
                        Box(f(a, 0), f(a, 1), f(a, 2), f(a, 3), f(a, 4), kind, v, col, stop)
                    })
            })
        }
        cached = out
        return out
    }

    private fun f(a: JSONArray, i: Int) = a.getDouble(i).toFloat()
}
