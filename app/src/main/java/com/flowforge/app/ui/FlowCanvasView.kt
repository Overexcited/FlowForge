package com.flowforge.app.ui

import android.content.Context
import android.graphics.*
import android.view.*
import org.json.JSONObject
import com.flowforge.app.model.*
import kotlin.math.*

class FlowCanvasView(context: Context) : View(context) {
    init { isFocusable = true; isClickable = true }
    var document: FlowDocument = FlowDocument()
        set(value) { field = value; invalidate() }
    var selectedElementId: String? = null
    var selectedConnectionId: String? = null
    var onSelectionChanged: (() -> Unit)? = null
    var onNotesTap: ((FlowElement) -> Unit)? = null
    var onConnectionRequested: ((String, String, ConnectionSide, ConnectionSide, List<PointF>) -> Unit)? = null
    var onConnectionCancelled: (() -> Unit)? = null
    var onCustomShapeFinished: ((List<PointF>) -> Unit)? = null
    var onMoveFinished: ((FlowElement, Float, Float) -> Unit)? = null
    var onResizeFinished: ((FlowElement, Float, Float, Float, Float) -> Unit)? = null
    var gridVisible = true
    var gridSize = 40f
    var darkMode = false
    var connectionMode = false
        private set
    var customShapeMode = false
        private set
    var customShapeTargetId: String? = null
        private set
    private var connectionStartId: String? = null
    private var connectionStartSide: ConnectionSide? = null
    private val customGesture = mutableListOf<PointF>()
    // When a popup menu is open, hide only the block-rendering layer beneath it.
    // The grid and connections remain visible through the popup's transparent gaps.
    private var popupBlockOcclusion: RectF? = null

    fun setPopupBlockOcclusion(rectInView: RectF?) {
        popupBlockOcclusion = rectInView?.let { RectF(it) }
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f; private var panX = 0f; private var panY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var lastScaleFocusX = 0f; private var lastScaleFocusY = 0f
    private var dragId: String? = null; private var dragOffsetX = 0f; private var dragOffsetY = 0f
    private var startMoveX = 0f; private var startMoveY = 0f
    private var resizeId: String? = null; private var resizeHandle = Handle.NONE
    private var startResize = RectF(); private var lastNotesButton = RectF()
    private var gestureMoved = false
    private var dragMovedByGrid = false
    private var pressElementId: String? = null
    private var pressConnectionId: String? = null
    private var connectDownX = 0f
    private var connectDownY = 0f
    private enum class Handle { NONE, TL, T, TR, L, R, BL, B, BR }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastScaleFocusX = detector.focusX
            lastScaleFocusY = detector.focusY
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            val fx = detector.focusX; val fy = detector.focusY
            // Move the viewport with the two-finger focus first, then scale around
            // the new focus point. This gives true two-finger pan + pinch zoom.
            panX += fx - lastScaleFocusX
            panY += fy - lastScaleFocusY
            scale = (scale * detector.scaleFactor).coerceIn(0.25f, 5f)
            panX = fx - (fx - panX) * (scale / old)
            panY = fy - (fy - panY) * (scale / old)
            lastScaleFocusX = fx
            lastScaleFocusY = fy
            gestureMoved = true; invalidate(); return true
        }
    })

    override fun onDraw(c: Canvas) {
        c.drawColor(if (darkMode) Color.rgb(15,23,42) else Color.WHITE)
        c.save(); c.translate(panX, panY); c.scale(scale, scale)
        drawContent(c, true)
        c.restore()
    }
    fun drawContentForExport(c: Canvas) { drawContent(c, false) }

    fun renderElementThumbnail(e: FlowElement, widthPx: Int, heightPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (darkMode) 0xff0f172a.toInt() else Color.WHITE)
        val pad = widthPx * 0.12f
        val availW = (widthPx - pad * 2).coerceAtLeast(1f)
        val availH = (heightPx - pad * 2).coerceAtLeast(1f)
        val s = min(availW / e.width, availH / e.height)
        val drawW = e.width * s
        val drawH = e.height * s
        c.save()
        c.translate((widthPx - drawW) / 2f, (heightPx - drawH) / 2f)
        c.scale(s, s)
        drawElement(c, e.copy(x = 0f, y = 0f))
        c.restore()
        return bmp
    }

    private fun drawContent(c: Canvas, includeSelection: Boolean) {
        val interactive = includeSelection
        if (interactive && gridVisible) drawGrid(c)
        document.connections.forEach { drawConnection(c, it) }
        val occlusionWorld = popupBlockOcclusion?.let { r ->
            RectF(
                (r.left - panX) / scale,
                (r.top - panY) / scale,
                (r.right - panX) / scale,
                (r.bottom - panY) / scale
            )
        }
        document.elements.forEach { element ->
            if (occlusionWorld == null || !RectF.intersects(RectF(element.x, element.y, element.x + element.width, element.y + element.height), occlusionWorld)) {
                drawElement(c, element)
            }
        }
        if (includeSelection && connectionMode) document.elements.forEach { drawConnectionTargets(c, it) }
        if (customShapeMode && customGesture.size > 1) drawCustomPreview(c)
        if (includeSelection) selectedElement()?.let { drawSelection(c, it) }

        // PopupWindow is a separate window, so merely skipping block drawing can
        // still leave previously composited canvas pixels visible through its
        // transparent gaps. Paint a canvas-layer mask over the popup footprint
        // instead: it hides blocks, connections and selection graphics while
        // redrawing the grid so the popup background remains visually transparent.
        if (includeSelection) popupBlockOcclusion?.let { r ->
            val mask = RectF(
                (r.left - panX) / scale,
                (r.top - panY) / scale,
                (r.right - panX) / scale,
                (r.bottom - panY) / scale
            )
            c.save()
            c.clipRect(mask)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = if (darkMode) Color.rgb(15, 23, 42) else Color.WHITE
            c.drawRect(mask, paint)
            if (interactive && gridVisible) drawGrid(c)
            c.restore()
        }
    }
    private fun drawGrid(c: Canvas) {
        gridPaint.color = if (darkMode) 0x405b7088 else 0x30475a6b; gridPaint.strokeWidth = 1f
        val left = floor((-panX / scale) / gridSize).toInt() * gridSize
        val top = floor((-panY / scale) / gridSize).toInt() * gridSize
        val right = ((width - panX) / scale) + gridSize; val bottom = ((height - panY) / scale) + gridSize
        var x=left; while(x<=right){c.drawLine(x,top,x,bottom,gridPaint);x+=gridSize};var y=top;while(y<=bottom){c.drawLine(left,y,right,y,gridPaint);y+=gridSize}
    }
    private fun outlineWidth(e:FlowElement)=when(e.outlineThickness){LineThickness.DEFAULT->2.5f;LineThickness.MEDIUM->7.5f;LineThickness.LARGE->15f}
    private fun connectionWidth(c:FlowConnection)=when(c.thickness){LineThickness.DEFAULT->3.5f;LineThickness.MEDIUM->7f;LineThickness.LARGE->14f}
    private fun textSizeValue(size: TextSize): Float = when(size){
        TextSize.SMALL -> 16f
        TextSize.NORMAL -> 21f
        TextSize.MEDIUM -> 25f
        TextSize.LARGE -> 30f
        TextSize.EXTRA_LARGE -> 36f
        TextSize.HUGE -> 44f
    }
    private fun textFontFamily(font: TextFont): String = when(font){
        TextFont.SANS -> "sans-serif"
        TextFont.SERIF -> "serif"
        TextFont.MONOSPACE -> "monospace"
        TextFont.SANS_CONDENSED -> "sans-serif-condensed"
        TextFont.SANS_LIGHT -> "sans-serif-light"
    }
    private fun applyLabelStyle(size: TextSize, bold: Boolean, italic: Boolean, underline: Boolean, font: TextFont){
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        textPaint.typeface = Typeface.create(textFontFamily(font), style)
        textPaint.textSize = textSizeValue(size)
        textPaint.flags = Paint.ANTI_ALIAS_FLAG or if(underline) Paint.UNDERLINE_TEXT_FLAG else 0
    }

    private fun drawElement(c:Canvas,e:FlowElement){
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        paint.pathEffect=null
        if(e.customPoints.size < 3 && e.fillColor!=null){paint.style=Paint.Style.FILL;paint.color=e.fillColor!!;drawShape(c,e,r)}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=if(e.id==selectedElementId)maxOf(5f,outlineWidth(e)) else outlineWidth(e)
        paint.color=if(e.id==selectedElementId)0xff2563eb.toInt() else(e.outlineColor?:if(darkMode)0xff94a3b8.toInt() else 0xff334155.toInt())
        paint.pathEffect=when(e.outlineLineStyle){LineStyle.DASHED->DashPathEffect(floatArrayOf(18f,12f),0f);LineStyle.DOTTED->DashPathEffect(floatArrayOf(4f,10f),0f);else->null}
        if(e.customPoints.size >= 3) drawCustomShape(c,e,r) else drawShape(c,e,r)
        paint.pathEffect=null
        textPaint.color=e.labelColor?:if(darkMode)Color.WHITE else 0xff172033.toInt();applyLabelStyle(e.labelTextSize,e.labelBold,e.labelItalic,e.labelUnderline,e.labelFont)
        val size=textSizeValue(e.labelTextSize);val maxChars=max(6,(e.width/(size*.60f)).toInt());val lines=wrap(e.label,maxChars);val lineH=size*1.16f;val base=e.y+e.height/2f-(lines.size-1)*lineH/2f+size*.36f
        lines.forEachIndexed{i,s->c.drawText(s,e.x+e.width/2f-textPaint.measureText(s)/2f,base+i*lineH,textPaint)}
        if(e.notes.isNotBlank())drawBadge(c,e.x+e.width-14f,e.y+14f,true)
    }
    private fun drawCustomShape(c:Canvas,e:FlowElement,r:RectF){
        val pts=e.customPoints
        if(pts.size<3){ drawShape(c,e,r); return }
        val path=smoothedClosedPath(pts.map{PointF(r.left+it.x*r.width(), r.top+it.y*r.height())}, 1)
        if(e.fillColor!=null){ val old=paint.style; paint.style=Paint.Style.FILL; paint.color=e.fillColor!!; c.drawPath(path,paint); paint.style=old }
        paint.style=Paint.Style.STROKE; c.drawPath(path,paint)
    }
    private fun smoothedClosedPath(points:List<PointF>,passes:Int):Path{
        var cur=points
        repeat(passes){
            if(cur.size<3) return@repeat
            val next=mutableListOf<PointF>()
            for(i in cur.indices){
                val a=cur[i]; val b=cur[(i+1)%cur.size]
                next += PointF(a.x*.75f+b.x*.25f,a.y*.75f+b.y*.25f)
                next += PointF(a.x*.25f+b.x*.75f,a.y*.25f+b.y*.75f)
            }
            cur=next
        }
        val p=Path(); val n=cur.size
        val first=cur[0]; val last=cur[n-1]; p.moveTo((first.x+last.x)/2f,(first.y+last.y)/2f)
        for(i in cur.indices){ val q=cur[i]; val next=cur[(i+1)%n]; val mid=PointF((q.x+next.x)/2f,(q.y+next.y)/2f); p.quadTo(q.x,q.y,mid.x,mid.y) }
        p.close(); return p
    }
    private fun drawCustomPreview(c:Canvas){
        if(customGesture.size<2)return
        paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; paint.color=0xff2563eb.toInt(); paint.pathEffect=null
        val path=Path(); path.moveTo(customGesture[0].x,customGesture[0].y); for(i in 1 until customGesture.size) path.lineTo(customGesture[i].x,customGesture[i].y); c.drawPath(path,paint)
    }

    private fun drawShape(c:Canvas,e:FlowElement,r:RectF){when(e.shape){
        ShapeType.RECTANGLE->c.drawRect(r,paint)
        ShapeType.ROUNDED->c.drawRoundRect(r,18f,18f,paint)
        ShapeType.EXTRA_ROUNDED->c.drawRoundRect(r,min(r.width(),r.height())*.22f,min(r.width(),r.height())*.22f,paint)
        ShapeType.OVAL->c.drawOval(r,paint)
        ShapeType.TRIANGLE->c.drawPath(Path().apply{moveTo(r.centerX(),r.top);lineTo(r.right,r.bottom);lineTo(r.left,r.bottom);close()},paint)
        ShapeType.STAR->c.drawPath(starPath(r),paint)
        ShapeType.CLOUD->c.drawPath(cloudPath(r),paint)
        ShapeType.TRAPEZOID_TOP_SHORT->c.drawPath(Path().apply{val inset=r.width()*.22f;moveTo(r.left+inset,r.top);lineTo(r.right-inset,r.top);lineTo(r.right,r.bottom);lineTo(r.left,r.bottom);close()},paint)
        ShapeType.TRAPEZOID_BOTTOM_SHORT->c.drawPath(Path().apply{val inset=r.width()*.22f;moveTo(r.left,r.top);lineTo(r.right,r.top);lineTo(r.right-inset,r.bottom);lineTo(r.left+inset,r.bottom);close()},paint)
        ShapeType.DIAMOND->c.drawPath(Path().apply{moveTo(r.centerX(),r.top);lineTo(r.right,r.centerY());lineTo(r.centerX(),r.bottom);lineTo(r.left,r.centerY());close()},paint)
        ShapeType.PARALLELOGRAM->c.drawPath(Path().apply{val s=min(25f,r.width()*.18f);moveTo(r.left+s,r.top);lineTo(r.right,r.top);lineTo(r.right-s,r.bottom);lineTo(r.left,r.bottom);close()},paint)
        ShapeType.CYLINDER->{val ry=min(18f,r.height()/5f);c.drawRoundRect(r,ry,ry,paint)}
        ShapeType.DOCUMENT->c.drawPath(Path().apply{moveTo(r.left,r.top);lineTo(r.right,r.top);lineTo(r.right,r.bottom-14);quadTo(r.centerX(),r.bottom+10,r.left,r.bottom-14);close()},paint)
        ShapeType.HEXAGON->c.drawPath(Path().apply{val s=min(r.width()*.18f,r.height()*.35f);moveTo(r.left+s,r.top);lineTo(r.right-s,r.top);lineTo(r.right,r.centerY());lineTo(r.right-s,r.bottom);lineTo(r.left+s,r.bottom);lineTo(r.left,r.centerY());close()},paint)
        ShapeType.CIRCLE->c.drawOval(r,paint)
    }}
    private fun starPath(r:RectF):Path{
        val p=Path();val cx=r.centerX();val cy=r.centerY();val outerX=r.width()*.5f;val outerY=r.height()*.5f;val innerX=outerX*.42f;val innerY=outerY*.42f
        for(i in 0 until 10){val a=(-Math.PI/2.0)+(i*Math.PI/5.0);val ox=if(i%2==0)outerX else innerX;val oy=if(i%2==0)outerY else innerY;val x=cx+(kotlin.math.cos(a)*ox).toFloat();val y=cy+(kotlin.math.sin(a)*oy).toFloat();if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();return p
    }
    private fun cloudPath(r:RectF):Path{
        val p=Path();val w=r.width();val h=r.height();val base=r.bottom-h*.16f;
        p.moveTo(r.left+w*.16f,base);
        p.cubicTo(r.left+w*.02f,base,r.left+w*.01f,r.top+h*.52f,r.left+w*.18f,r.top+h*.47f);
        p.cubicTo(r.left+w*.18f,r.top+h*.20f,r.left+w*.40f,r.top+h*.08f,r.left+w*.50f,r.top+h*.28f);
        p.cubicTo(r.left+w*.64f,r.top+h*.02f,r.right-w*.10f,r.top+h*.15f,r.right-w*.14f,r.top+h*.42f);
        p.cubicTo(r.right+w*.02f,r.top+h*.46f,r.right-w*.00f,base,r.right-w*.20f,base);
        p.lineTo(r.left+w*.16f,base);p.close();return p
    }
    private fun drawConnectionTargets(c:Canvas,e:FlowElement){
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        val hs=10f
        // Interaction points stay on the rectangular selection box.  The
        // finished connector itself is anchored to the rendered shape outline.
        val points=listOf(
            ConnectionSide.TOP to explicitEndpoint(e,ConnectionSide.TOP),
            ConnectionSide.RIGHT to explicitEndpoint(e,ConnectionSide.RIGHT),
            ConnectionSide.BOTTOM to explicitEndpoint(e,ConnectionSide.BOTTOM),
            ConnectionSide.LEFT to explicitEndpoint(e,ConnectionSide.LEFT)
        )
        points.forEach{(side,p)->
            val active=connectionStartId==e.id && connectionStartSide==side
            paint.style=Paint.Style.FILL;paint.color=if(active)0xff2563eb.toInt() else Color.WHITE;c.drawCircle(p.x,p.y,hs,paint)
            paint.style=Paint.Style.STROKE;paint.color=0xff2563eb.toInt();paint.strokeWidth=3f;c.drawCircle(p.x,p.y,hs,paint)
        }
    }
    private fun drawSelection(c:Canvas,e:FlowElement){
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=0xff2563eb.toInt();c.drawRect(r,paint)
        if(connectionMode){
            // Connection points for every block are drawn by drawContent().
        } else if(!customShapeMode){
            val hs=10f;handlePoints(r).forEach{p->paint.style=Paint.Style.FILL;paint.color=Color.WHITE;c.drawCircle(p.x,p.y,hs,paint);paint.style=Paint.Style.STROKE;paint.color=0xff2563eb.toInt();paint.strokeWidth=3f;c.drawCircle(p.x,p.y,hs,paint)}
        }
        if(e.notes.isNotBlank())lastNotesButton=RectF(r.right-30f,r.top-30f,r.right+2f,r.top+2f)else lastNotesButton.setEmpty()
    }
    private fun drawBadge(c:Canvas,x:Float,y:Float,info:Boolean){paint.style=Paint.Style.FILL;paint.color=0xfff59e0b.toInt();c.drawCircle(x,y,10f,paint);textPaint.color=Color.WHITE;textPaint.typeface=Typeface.DEFAULT;textPaint.textSize=13f;textPaint.flags=Paint.ANTI_ALIAS_FLAG;c.drawText(if(info)"i" else "!",x-2.3f,y+4.5f,textPaint)}

    private fun drawConnection(c:Canvas,con:FlowConnection){
        val a=document.elements.firstOrNull{it.id==con.fromId}?:return;val b=document.elements.firstOrNull{it.id==con.toId}?:return
        val pair=document.connections.filter{(it.fromId==con.fromId&&it.toId==con.toId)||(it.fromId==con.toId&&it.toId==con.fromId)}.sortedBy{it.id};val idx=pair.indexOfFirst{it.id==con.id}.coerceAtLeast(0)
        val auto=connectionEndpoints(a,b,idx,pair.size);val p1=if(con.fromSide==ConnectionSide.AUTO)auto.first else faceEndpoint(a,con.fromSide,idx,pair.size,sideLength(b,con.toSide));val p2=if(con.toSide==ConnectionSide.AUTO)auto.second else faceEndpoint(b,con.toSide,idx,pair.size,sideLength(a,con.fromSide))
        val path=if(con.fromSide!=ConnectionSide.AUTO && con.toSide!=ConnectionSide.AUTO){buildDynamicRoutedPath(a,b,p1,p2,con.fromSide,con.toSide)}else buildConnectionPath(p1,p2,con.bendX,con.bendY,idx)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=if(con.id==selectedConnectionId)7f else connectionWidth(con);paint.color=if(con.id==selectedConnectionId)0xff2563eb.toInt() else con.color
        paint.pathEffect=when(con.lineStyle){LineStyle.DASHED->DashPathEffect(floatArrayOf(18f,12f),0f);LineStyle.DOTTED->DashPathEffect(floatArrayOf(4f,10f),0f);else->null};c.drawPath(path,paint);paint.pathEffect=null
        if(con.arrowType==ArrowType.REPEATED)drawRepeatedArrows(c,path)else if(con.arrowType!=ArrowType.NONE)drawConnectionArrows(c,path,con.arrowType)
        val mid=pathMidpoint(path);if(con.label.isNotBlank()){
            textPaint.color=con.labelColor?:if(darkMode)Color.WHITE else 0xff334155.toInt()
            applyLabelStyle(con.labelTextSize,con.labelBold,con.labelItalic,con.labelUnderline,con.labelFont)
            val size=textSizeValue(con.labelTextSize)
            val lines=wrap(con.label,max(6,(size*12f).toInt()))
            val lineH=size*1.16f
            val base=mid.y-(lines.size-1)*lineH/2f+size*.36f
            lines.forEachIndexed{i,line->c.drawText(line,mid.x+6-textPaint.measureText(line)/2f,base+i*lineH,textPaint)}
        };if(con.notes.isNotBlank())drawBadge(c,mid.x+12,mid.y-18,false)
    }
    private fun preferredSide(a:FlowElement,b:FlowElement):ConnectionSide{val dx=b.x+b.width/2f-(a.x+a.width/2f);val dy=b.y+b.height/2f-(a.y+a.height/2f);return if(abs(dy)>=abs(dx)){if(dy>=0)ConnectionSide.BOTTOM else ConnectionSide.TOP}else{if(dx>=0)ConnectionSide.RIGHT else ConnectionSide.LEFT}}
    private fun distributedSide(preferred:ConnectionSide,index:Int):ConnectionSide{if(preferred==ConnectionSide.AUTO)return ConnectionSide.AUTO;val order=when(preferred){ConnectionSide.TOP->arrayOf(ConnectionSide.TOP,ConnectionSide.RIGHT,ConnectionSide.LEFT,ConnectionSide.BOTTOM);ConnectionSide.RIGHT->arrayOf(ConnectionSide.RIGHT,ConnectionSide.BOTTOM,ConnectionSide.TOP,ConnectionSide.LEFT);ConnectionSide.BOTTOM->arrayOf(ConnectionSide.BOTTOM,ConnectionSide.LEFT,ConnectionSide.RIGHT,ConnectionSide.TOP);ConnectionSide.LEFT->arrayOf(ConnectionSide.LEFT,ConnectionSide.TOP,ConnectionSide.BOTTOM,ConnectionSide.RIGHT);else->arrayOf(ConnectionSide.TOP)};return order[index%order.size]}
    private fun connectionEndpoints(a: FlowElement, b: FlowElement, index: Int, count: Int): Pair<PointF, PointF> {
        val pref = preferredSide(a, b)
        val sa = distributedSide(pref, index)
        val sb = when (sa) {
            ConnectionSide.TOP -> ConnectionSide.BOTTOM
            ConnectionSide.RIGHT -> ConnectionSide.LEFT
            ConnectionSide.BOTTOM -> ConnectionSide.TOP
            ConnectionSide.LEFT -> ConnectionSide.RIGHT
            else -> ConnectionSide.AUTO
        }
        fun point(e: FlowElement, side: ConnectionSide, offset: Float): PointF =
            shapeBoundaryEndpoint(e,side,offset)
        val lane = if (count <= 1) 0f else {
            ((index - (count - 1) / 2f) * minOf(a.width, a.height) * .28f)
                .coerceIn(-minOf(a.width, a.height) * .42f, minOf(a.width, a.height) * .42f)
        }
        return point(a, sa, lane) to point(b, sb, lane)
    }
    // Finished connection anchors are based on the actual rendered outline,
    // while interaction points remain on the rectangular selection box. This
    // matters for irregular shapes such as STAR, CLOUD and DOCUMENT whose
    // visible outline does not reach every edge of their bounding rectangle.
    private fun shapeBoundaryEndpoint(e:FlowElement,side:ConnectionSide,offset:Float):PointF{
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        if(e.customPoints.size<3 && (e.shape==ShapeType.RECTANGLE || e.shape==ShapeType.ROUNDED || e.shape==ShapeType.EXTRA_ROUNDED || e.shape==ShapeType.OVAL || e.shape==ShapeType.CIRCLE)) {
            return when(side){
                ConnectionSide.TOP->PointF((r.centerX()+offset).coerceIn(r.left+8f,r.right-8f),r.top)
                ConnectionSide.RIGHT->PointF(r.right,(r.centerY()+offset).coerceIn(r.top+8f,r.bottom-8f))
                ConnectionSide.BOTTOM->PointF((r.centerX()+offset).coerceIn(r.left+8f,r.right-8f),r.bottom)
                ConnectionSide.LEFT->PointF(r.left,(r.centerY()+offset).coerceIn(r.top+8f,r.bottom-8f))
                else->PointF(r.centerX(),r.centerY())
            }
        }
        val path=if(e.customPoints.size>=3) {
            smoothedClosedPath(e.customPoints.map{PointF(r.left+it.x*r.width(),r.top+it.y*r.height())},1)
        } else shapePath(e,r)
        val samples=512
        val pts=ArrayList<PointF>(samples+1)
        val m=PathMeasure(path,true)
        val pos=FloatArray(2)
        if(m.length>0f){
            for(i in 0..samples){
                m.getPosTan(m.length*i.toFloat()/samples,pos,null)
                pts+=PointF(pos[0],pos[1])
            }
        }
        val wantedX=(r.centerX()+offset).coerceIn(r.left,r.right)
        val wantedY=(r.centerY()+offset).coerceIn(r.top,r.bottom)
        var best:PointF?=null
        fun consider(p:PointF){
            when(side){
                ConnectionSide.TOP->if(best==null||p.y<best!!.y)best=p
                ConnectionSide.BOTTOM->if(best==null||p.y>best!!.y)best=p
                ConnectionSide.LEFT->if(best==null||p.x<best!!.x)best=p
                ConnectionSide.RIGHT->if(best==null||p.x>best!!.x)best=p
                else->Unit
            }
        }
        fun lineIntersect(a:PointF,b:PointF):PointF?{
            if(side==ConnectionSide.TOP||side==ConnectionSide.BOTTOM){
                val dx=b.x-a.x
                if(abs(dx)<0.0001f){ if(abs(a.x-wantedX)<2f)return PointF(wantedX,a.y); return null }
                val t=(wantedX-a.x)/dx
                if(t>=0f&&t<=1f)return PointF(wantedX,a.y+(b.y-a.y)*t)
            }else{
                val dy=b.y-a.y
                if(abs(dy)<0.0001f){ if(abs(a.y-wantedY)<2f)return PointF(a.x,wantedY); return null }
                val t=(wantedY-a.y)/dy
                if(t>=0f&&t<=1f)return PointF(a.x+(b.x-a.x)*t,wantedY)
            }
            return null
        }
        for(i in 0 until pts.lastIndex){ lineIntersect(pts[i],pts[i+1])?.let(::consider) }
        return best ?: when(side){
            ConnectionSide.TOP->PointF(wantedX,r.top)
            ConnectionSide.RIGHT->PointF(r.right,wantedY)
            ConnectionSide.BOTTOM->PointF(wantedX,r.bottom)
            ConnectionSide.LEFT->PointF(r.left,wantedY)
            else->PointF(r.centerX(),r.centerY())
        }
    }

    private fun shapePath(e:FlowElement,r:RectF):Path{
        val p=Path()
        when(e.shape){
            ShapeType.TRIANGLE->p.apply{moveTo(r.centerX(),r.top);lineTo(r.right,r.bottom);lineTo(r.left,r.bottom);close()}
            ShapeType.STAR->return starPath(r)
            ShapeType.CLOUD->return cloudPath(r)
            ShapeType.TRAPEZOID_TOP_SHORT->p.apply{val inset=r.width()*.22f;moveTo(r.left+inset,r.top);lineTo(r.right-inset,r.top);lineTo(r.right,r.bottom);lineTo(r.left,r.bottom);close()}
            ShapeType.TRAPEZOID_BOTTOM_SHORT->p.apply{val inset=r.width()*.22f;moveTo(r.left,r.top);lineTo(r.right,r.top);lineTo(r.right-inset,r.bottom);lineTo(r.left+inset,r.bottom);close()}
            ShapeType.DIAMOND->p.apply{moveTo(r.centerX(),r.top);lineTo(r.right,r.centerY());lineTo(r.centerX(),r.bottom);lineTo(r.left,r.centerY());close()}
            ShapeType.PARALLELOGRAM->p.apply{val s=min(25f,r.width()*.18f);moveTo(r.left+s,r.top);lineTo(r.right,r.top);lineTo(r.right-s,r.bottom);lineTo(r.left,r.bottom);close()}
            ShapeType.CYLINDER->{val ry=min(18f,r.height()/5f);p.addRoundRect(r,ry,ry,Path.Direction.CW)}
            ShapeType.DOCUMENT->p.apply{moveTo(r.left,r.top);lineTo(r.right,r.top);lineTo(r.right,r.bottom-14);quadTo(r.centerX(),r.bottom+10,r.left,r.bottom-14);close()}
            ShapeType.HEXAGON->p.apply{val s=min(r.width()*.18f,r.height()*.35f);moveTo(r.left+s,r.top);lineTo(r.right-s,r.top);lineTo(r.right,r.centerY());lineTo(r.right-s,r.bottom);lineTo(r.left+s,r.bottom);lineTo(r.left,r.centerY());close()}
            ShapeType.ROUNDED,ShapeType.EXTRA_ROUNDED->p.addRoundRect(r,if(e.shape==ShapeType.ROUNDED)18f else min(r.width(),r.height())*.22f,if(e.shape==ShapeType.ROUNDED)18f else min(r.width(),r.height())*.22f,Path.Direction.CW)
            ShapeType.OVAL,ShapeType.CIRCLE->p.addOval(r,Path.Direction.CW)
            else->p.addRect(r,Path.Direction.CW)
        }
        return p
    }
    private fun endpointSide(e:FlowElement,p:PointF):ConnectionSide{val dl=abs(p.x-e.x);val dr=abs(p.x-(e.x+e.width));val dt=abs(p.y-e.y);val db=abs(p.y-(e.y+e.height));return when(minOf(dl,dr,dt,db)){dt->ConnectionSide.TOP;dr->ConnectionSide.RIGHT;db->ConnectionSide.BOTTOM;else->ConnectionSide.LEFT}}

    private fun buildConnectionPath(p1:PointF,p2:PointF,bx:Float,by:Float,index:Int):Path{val p=Path();p.moveTo(p1.x,p1.y);if(bx!=0f||by!=0f)p.quadTo((p1.x+p2.x)/2f+bx,(p1.y+p2.y)/2f+by,p2.x,p2.y)else{val dx=p2.x-p1.x;val dy=p2.y-p1.y;if(abs(dy)>=abs(dx)){val mid=(p1.y+p2.y)/2f+if(index%2==0)0f else 18f;p.cubicTo(p1.x,mid,p2.x,mid,p2.x,p2.y)}else{val mid=(p1.x+p2.x)/2f+if(index%2==0)0f else 18f;p.cubicTo(mid,p1.y,mid,p2.y,p2.x,p2.y)}};return p}
    private fun sideLength(e:FlowElement,side:ConnectionSide):Float = when(side){
        ConnectionSide.TOP,ConnectionSide.BOTTOM -> e.width
        ConnectionSide.LEFT,ConnectionSide.RIGHT -> e.height
        else -> min(e.width,e.height)
    }

    private fun faceEndpoint(e:FlowElement,side:ConnectionSide,index:Int,count:Int,pairedFaceLength:Float):PointF {
        if(count<=1)return shapeBoundaryEndpoint(e,side,0f)
        val faceLen=min(sideLength(e,side),pairedFaceLength).coerceAtLeast(24f)
        val usable=(faceLen-24f).coerceAtLeast(24f)
        val spacing=min(48f,usable/(count-1).coerceAtLeast(1))
        val offset=(index-(count-1)/2f)*spacing
        return shapeBoundaryEndpoint(e,side,offset)
    }

    private data class RouteObstacle(val points:List<PointF>, val clearance:Float)

    private fun buildDynamicRoutedPath(a:FlowElement,b:FlowElement,start:PointF,end:PointF,fromSide:ConnectionSide,toSide:ConnectionSide):Path {
        val clearance=12f
        val sourceOut=shapePortLead(a,start,fromSide,18f)
        val targetOut=shapePortLead(b,end,toSide,18f)
        val obstacles=routeObstacles(a,b,clearance)
        if(segmentClear(sourceOut,targetOut,obstacles)){
            return buildContinuousRoutePath(listOf(start,sourceOut,targetOut,end),obstacles)
        }
        val middle=visibilityRoute(sourceOut,targetOut,obstacles,0)
        val raw=mutableListOf<PointF>()
        raw+=start
        raw+=sourceOut
        raw.addAll(middle.drop(1).dropLast(1))
        raw+=targetOut
        raw+=end
        val simplified=simplifyRoute(raw,obstacles)
        return buildContinuousRoutePath(if(simplified.size>=2)simplified else listOf(start,end),obstacles)
    }

    private fun buildContinuousRoutePath(points:List<PointF>,obstacles:List<RouteObstacle>):Path {
        val cleaned=removeRedundantRoutePoints(points)
        val straight=Path()
        if(cleaned.isEmpty())return straight
        straight.moveTo(cleaned[0].x,cleaned[0].y)
        if(cleaned.size==1)return straight
        if(cleaned.size==2){straight.lineTo(cleaned[1].x,cleaned[1].y);return straight}

        // Keep the route straight on every leg and round only the actual changes
        // of direction. This produces a flowing path around obstacles instead of
        // bending the entire route through the waypoints.
        var radius=56f
        repeat(10){
            val candidate=buildFilletedRouteCandidate(cleaned,radius)
            if(curvePathClear(candidate,obstacles,ignoreEndpointObstacles=true))return candidate
            radius*=.82f
        }

        // A smaller fillet is preferable to falling back to a sharp polyline.
        // The route points are already outside the obstacle geometry, so this
        // final candidate preserves the intended smooth transition as closely as
        // possible while remaining conservative.
        return buildFilletedRouteCandidate(cleaned,14f)
    }

    private data class RouteFillet(val inPoint:PointF,val outPoint:PointF,val c1:PointF,val c2:PointF)

    private fun buildFilletedRouteCandidate(points:List<PointF>,radius:Float):Path {
        val fillets=ArrayList<RouteFillet?>(points.size)
        fillets+=null
        for(i in 1 until points.lastIndex){
            val a=points[i-1]
            val b=points[i]
            val c=points[i+1]
            val inDx=b.x-a.x
            val inDy=b.y-a.y
            val inLen=maxOf(.001f,hypot(inDx,inDy))
            val outDx=c.x-b.x
            val outDy=c.y-b.y
            val outLen=maxOf(.001f,hypot(outDx,outDy))
            val inUx=inDx/inLen
            val inUy=inDy/inLen
            val outUx=outDx/outLen
            val outUy=outDy/outLen
            val towardPreviousX=-inUx
            val towardPreviousY=-inUy
            val dot=(towardPreviousX*outUx+towardPreviousY*outUy).coerceIn(-.9999f,.9999f)
            val theta=acos(dot)
            if(theta<.08f || theta>3.05f){
                fillets+=null
                continue
            }
            val tanHalf=maxOf(.0001f,tan(theta/2f))
            val tangentDistance=minOf(radius/tanHalf,inLen*.38f,outLen*.38f)
            if(tangentDistance<2f){
                fillets+=null
                continue
            }
            val actualRadius=tangentDistance*tanHalf
            val inPoint=PointF(b.x-inUx*tangentDistance,b.y-inUy*tangentDistance)
            val outPoint=PointF(b.x+outUx*tangentDistance,b.y+outUy*tangentDistance)
            val handle=(4f/3f)*tan(theta/4f)*actualRadius
            val c1=PointF(inPoint.x+inUx*handle,inPoint.y+inUy*handle)
            val c2=PointF(outPoint.x-outUx*handle,outPoint.y-outUy*handle)
            fillets+=RouteFillet(inPoint,outPoint,c1,c2)
        }
        fillets+=null

        val p=Path()
        p.moveTo(points[0].x,points[0].y)
        for(i in 1 until points.lastIndex){
            val f=fillets[i]
            if(f==null){
                p.lineTo(points[i].x,points[i].y)
            }else{
                p.lineTo(f.inPoint.x,f.inPoint.y)
                p.cubicTo(f.c1.x,f.c1.y,f.c2.x,f.c2.y,f.outPoint.x,f.outPoint.y)
            }
        }
        p.lineTo(points.last().x,points.last().y)
        return p
    }

    private fun normalized(a:PointF,b:PointF):PointF{
        val dx=b.x-a.x
        val dy=b.y-a.y
        val len=maxOf(.001f,hypot(dx,dy))
        return PointF(dx/len,dy/len)
    }

    private fun quadraticPathSegment(a:PointF,control:PointF,b:PointF):Path{
        val p=Path();p.moveTo(a.x,a.y);p.quadTo(control.x,control.y,b.x,b.y);return p
    }

    private fun curvePathClear(path:Path,obstacles:List<RouteObstacle>,ignoreEndpointObstacles:Boolean=false):Boolean{
        val m=PathMeasure(path,false)
        if(m.length<=0f)return true
        val pos=FloatArray(2)
        var d=0f
        var previous:PointF?=null
        while(d<=m.length){
            if(!m.getPosTan(d,pos,null))return false
            val current=PointF(pos[0],pos[1])
            val prev=previous
            if(prev!=null){
                var clear=false
                val endpointAllowance=18f
                if(ignoreEndpointObstacles && (d<=endpointAllowance || d>=m.length-endpointAllowance)){
                    clear=segmentClearAllowEndpoint(prev,current,obstacles)
                }else{
                    clear=segmentClear(prev,current,obstacles)
                }
                if(!clear)return false
            }
            previous=current
            d+=maxOf(3f,m.length/48f)
        }
        return true
    }

    private fun removeRedundantRoutePoints(points:List<PointF>):List<PointF>{
        if(points.size<=2)return points
        val out=mutableListOf<PointF>()
        for(pt in points)if(out.isEmpty()||hypot(pt.x-out.last().x,pt.y-out.last().y)>2f)out+=pt
        if(out.size<=2)return out
        var changed=true
        while(changed&&out.size>2){
            changed=false;var i=1
            while(i<out.lastIndex){
                val a=out[i-1];val b=out[i];val c=out[i+1]
                val cross=abs((b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x))
                val scale=maxOf(1f,hypot(b.x-a.x,b.y-a.y)+hypot(c.x-b.x,c.y-b.y))
                if(cross/scale<.035f){out.removeAt(i);changed=true}else i++
            }
        }
        return out
    }

    private fun buildRoutedPath(points:List<PointF>):Path=buildContinuousRoutePath(points,emptyList())
    private fun pathMidpoint(path:Path):PointF{val m=PathMeasure(path,false);if(m.length<=0f)return PointF();val pos=FloatArray(2);m.getPosTan(m.length/2f,pos,null);return PointF(pos[0],pos[1])}
    private fun drawConnectionArrows(c:Canvas,path:Path,type:ArrowType){val m=PathMeasure(path,false);if(m.length<=1f)return;val pos=FloatArray(2);val tan=FloatArray(2);fun sample(d:Float):Pair<PointF,PointF>{m.getPosTan(d.coerceIn(0f,m.length),pos,tan);return PointF(pos[0],pos[1]) to PointF(tan[0],tan[1])};val end=sample(m.length);val start=sample(min(20f,m.length));when(type){ArrowType.END->drawArrow(c,end.first.x-end.second.x*8f,end.first.y-end.second.y*8f,end.first.x,end.first.y,ArrowType.END);ArrowType.BOTH->{drawArrow(c,end.first.x-end.second.x*8f,end.first.y-end.second.y*8f,end.first.x,end.first.y,ArrowType.END);drawArrow(c,start.first.x+start.second.x*8f,start.first.y+start.second.y*8f,start.first.x,start.first.y,ArrowType.END)};ArrowType.CIRCLE->{paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;c.drawCircle(end.first.x,end.first.y,7f,paint)};ArrowType.DIAMOND->drawArrow(c,end.first.x-end.second.x*8f,end.first.y-end.second.y*8f,end.first.x,end.first.y,ArrowType.DIAMOND);else->Unit}}
    private fun drawArrow(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,type:ArrowType){val ang=atan2(y2-y1,x2-x1);val len=20f;val p=Path();if(type==ArrowType.DIAMOND){p.moveTo(x2,y2);p.lineTo(x2-len*.8f*cos(ang-.5f),y2-len*.8f*sin(ang-.5f));p.lineTo(x2-len*cos(ang),y2-len*sin(ang));p.lineTo(x2-len*.8f*cos(ang+.5f),y2-len*.8f*sin(ang+.5f));p.close()}else{p.moveTo(x2,y2);p.lineTo(x2-len*cos(ang-.5f),y2-len*sin(ang-.5f));p.lineTo(x2-len*cos(ang+.5f),y2-len*sin(ang+.5f));p.close()};paint.style=Paint.Style.FILL;paint.color=if(type==ArrowType.DIAMOND)paint.color else paint.color;c.drawPath(p,paint)}
    private fun drawRepeatedArrows(c:Canvas,path:Path){val m=PathMeasure(path,false);val pos=FloatArray(2);val tan=FloatArray(2);var d=55f;while(d<m.length-12f){if(m.getPosTan(d,pos,tan))drawArrow(c,pos[0]-tan[0]*8f,pos[1]-tan[1]*8f,pos[0],pos[1],ArrowType.END);d+=70f}}

    private fun simplifyGesture(points:List<PointF>):List<PointF>{
        if(points.size<2)return points.toList()
        val clean=mutableListOf<PointF>();var last=points.first();clean+=last
        for(i in 1 until points.lastIndex){val p=points[i];if(hypot(p.x-last.x,p.y-last.y)>=14f){clean+=p;last=p}}
        clean+=points.last();if(clean.size<3)return clean
        val turns=mutableListOf<PointF>();turns+=clean.first();var prevDir=direction(clean[0],clean[1]);var runStart=clean[0];var runDist=0f
        for(i in 1 until clean.lastIndex){val a=clean[i];val b=clean[i+1];val d=direction(a,b);runDist+=hypot(b.x-a.x,b.y-a.y);if(d!=prevDir&&runDist>=32f){turns+=a;prevDir=d;runDist=0f;runStart=a}}
        turns+=clean.last()
        return turns.take(6)
    }
    private fun direction(a:PointF,b:PointF):Int{val dx=b.x-a.x;val dy=b.y-a.y;return if(abs(dx)>=abs(dy))if(dx>=0)0 else 1 else if(dy>=0)2 else 3}
    private fun routeObstacles(a:FlowElement,b:FlowElement,clearance:Float):List<RouteObstacle>{
        val out=mutableListOf<RouteObstacle>()
        for(e in document.elements)if(e.id!=a.id&&e.id!=b.id)out+=RouteObstacle(shapeBoundaryPoints(e),clearance)
        out+=RouteObstacle(shapeBoundaryPoints(a),clearance);out+=RouteObstacle(shapeBoundaryPoints(b),clearance)
        return out
    }
    private fun shapeBoundaryPoints(e:FlowElement):List<PointF>{
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        val path=if(e.customPoints.size>=3)smoothedClosedPath(e.customPoints.map{PointF(r.left+it.x*r.width(),r.top+it.y*r.height())},1)else shapePath(e,r)
        val m=PathMeasure(path,true);if(m.length<=0f)return listOf(PointF(r.left,r.top),PointF(r.right,r.top),PointF(r.right,r.bottom),PointF(r.left,r.bottom))
        val pts=ArrayList<PointF>(128);val pos=FloatArray(2)
        for(i in 0 until 96)if(m.getPosTan(m.length*i/96f,pos,null)){val q=PointF(pos[0],pos[1]);if(pts.isEmpty()||hypot(q.x-pts.last().x,q.y-pts.last().y)>.5f)pts+=q}
        if(pts.size<=4)return pts
        val out=mutableListOf<PointF>();out+=pts.first()
        for(i in 1 until pts.lastIndex){
            val a=out.last();val b=pts[i];val c=pts[i+1]
            val cross=abs((b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x))
            val scale=maxOf(1f,hypot(b.x-a.x,b.y-a.y)+hypot(c.x-b.x,c.y-b.y))
            if(cross/scale>=.018f)out+=b
        }
        out+=pts.last()
        return out
    }

    private fun segmentClear(a:PointF,b:PointF,obs:List<RouteObstacle>):Boolean{for(o in obs){if(pointInPolygon(a,o.points)||pointInPolygon(b,o.points)||segmentNearPolygon(a,b,o.points,o.clearance))return false};return true}
    private fun segmentClearAllowEndpoint(a:PointF,b:PointF,obs:List<RouteObstacle>):Boolean{
        for(o in obs){
            val endpointNear=pointToPolygonDistance(a,o.points)<=o.clearance+2f || pointToPolygonDistance(b,o.points)<=o.clearance+2f
            if(endpointNear)continue
            if(segmentNearPolygon(a,b,o.points,o.clearance))return false
        }
        return true
    }
    private fun pointToPolygonDistance(p:PointF,poly:List<PointF>):Float{
        if(pointInPolygon(p,poly))return 0f
        var best=Float.POSITIVE_INFINITY
        for(i in poly.indices){
            val a=poly[i];val b=poly[(i+1)%poly.size]
            best=min(best,pointToSegmentDistance(p,a,b))
        }
        return best
    }
    private fun pointToSegmentDistance(p:PointF,a:PointF,b:PointF):Float{
        val vx=b.x-a.x;val vy=b.y-a.y;val len2=vx*vx+vy*vy
        if(len2<=.0001f)return hypot(p.x-a.x,p.y-a.y)
        val t=((p.x-a.x)*vx+(p.y-a.y)*vy)/len2
        val qx=a.x+vx*t.coerceIn(0f,1f);val qy=a.y+vy*t.coerceIn(0f,1f)
        return hypot(p.x-qx,p.y-qy)
    }
    private fun pointInPolygon(p:PointF,poly:List<PointF>):Boolean{if(poly.size<3)return false;var inside=false;var j=poly.lastIndex;for(i in poly.indices){val a=poly[i];val b=poly[j];if(((a.y>p.y)!=(b.y>p.y))&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)inside=!inside;j=i};return inside}
    private fun segmentNearPolygon(a:PointF,b:PointF,poly:List<PointF>,clearance:Float):Boolean{for(i in poly.indices){val c=poly[i];val d=poly[(i+1)%poly.size];if(segmentsIntersect(a,b,c,d)||segmentDistance(a,b,c,d)<clearance)return true};return false}
    private fun segmentDistance(a:PointF,b:PointF,c:PointF,d:PointF):Float{fun ps(p:PointF,x:PointF,y:PointF):Float{val vx=y.x-x.x;val vy=y.y-x.y;val l=vx*vx+vy*vy;if(l<=.0001f)return hypot(p.x-x.x,p.y-x.y);val t=((p.x-x.x)*vx+(p.y-x.y)*vy)/l;val qx=x.x+vx*t.coerceIn(0f,1f);val qy=x.y+vy*t.coerceIn(0f,1f);return hypot(p.x-qx,p.y-qy)};return minOf(ps(a,c,d),ps(b,c,d),ps(c,a,b),ps(d,a,b))}
    private fun segmentsIntersect(a:PointF,b:PointF,c:PointF,d:PointF):Boolean{
        fun cross(p:PointF,q:PointF,r:PointF):Float = (q.x-p.x)*(r.y-p.y)-(q.y-p.y)*(r.x-p.x)
        fun onSegment(p:PointF,q:PointF,r:PointF):Boolean = abs(cross(p,q,r))<.01f && r.x>=min(p.x,q.x)-.01f && r.x<=max(p.x,q.x)+.01f && r.y>=min(p.y,q.y)-.01f && r.y<=max(p.y,q.y)+.01f
        val c1=cross(a,b,c);val c2=cross(a,b,d);val c3=cross(c,d,a);val c4=cross(c,d,b)
        if(((c1>0f&&c2<0f)||(c1<0f&&c2>0f))&&((c3>0f&&c4<0f)||(c3<0f&&c4>0f)))return true
        return onSegment(a,b,c)||onSegment(a,b,d)||onSegment(c,d,a)||onSegment(c,d,b)
    }
    private fun curveClear(a:PointF,b:PointF,c:PointF,obs:List<RouteObstacle>):Boolean{var prev=a;for(i in 1..16){val t=i/16f;val u=1f-t;val q=PointF(u*u*a.x+2f*u*t*b.x+t*t*c.x,u*u*a.y+2f*u*t*b.y+t*t*c.y);if(!segmentClear(prev,q,obs))return false;prev=q};return true}
    private fun routeNodeForVertex(poly:List<PointF>,index:Int,distance:Float):PointF{
        val prev=poly[(index-1+poly.size)%poly.size]
        val cur=poly[index]
        val next=poly[(index+1)%poly.size]
        fun outwardNormal(a:PointF,b:PointF,ccw:Boolean):PointF{
            val dx=b.x-a.x
            val dy=b.y-a.y
            val l=maxOf(.001f,hypot(dx,dy))
            return if(ccw)PointF(dy/l,-dx/l)else PointF(-dy/l,dx/l)
        }
        var area=0f
        for(i in poly.indices){
            val a=poly[i]
            val b=poly[(i+1)%poly.size]
            area+=a.x*b.y-b.x*a.y
        }
        val ccw=area>0f
        val n1=outwardNormal(prev,cur,ccw)
        val n2=outwardNormal(cur,next,ccw)
        var nx=n1.x+n2.x
        var ny=n1.y+n2.y
        var len=maxOf(.001f,hypot(nx,ny))
        nx/=len
        ny/=len
        var candidate=PointF(cur.x+nx*distance,cur.y+ny*distance)
        // Concave vertices have the opposite outward bisector from convex
        // vertices. Test the candidate against the actual polygon and flip it
        // when necessary so the routing graph never deliberately enters a
        // concave notch of a shape such as a star.
        if(pointInPolygon(candidate,poly)){
            nx=-nx
            ny=-ny
            candidate=PointF(cur.x+nx*distance,cur.y+ny*distance)
        }
        return candidate
    }

    private fun routeConnection(a:FlowElement,b:FlowElement,fromSide:ConnectionSide,toSide:ConnectionSide,gesture:List<PointF>):List<PointF>{
        val start=shapeBoundaryEndpoint(a,fromSide,0f);val end=shapeBoundaryEndpoint(b,toSide,0f);val clearance=8f
        val sourceOut=shapePortLead(a,start,fromSide,12f);val targetOut=shapePortLead(b,end,toSide,12f);val obstacles=routeObstacles(a,b,clearance)
        if(segmentClear(sourceOut,targetOut,obstacles))return simplifyRoute(listOf(start,sourceOut,targetOut,end),obstacles)
        val middle=visibilityRoute(sourceOut,targetOut,obstacles,gestureBias(gesture));val raw=mutableListOf<PointF>();raw+=start;raw+=sourceOut;raw.addAll(middle.drop(1).dropLast(1));raw+=targetOut;raw+=end
        return simplifyRoute(raw,obstacles)
    }
    private fun shapePortLead(e:FlowElement,p:PointF,side:ConnectionSide,d:Float):PointF{
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        val cx=r.centerX();val cy=r.centerY()
        var dx=p.x-cx;var dy=p.y-cy
        if(abs(dx)+abs(dy)<.01f){
            dx=when(side){ConnectionSide.LEFT->-1f;ConnectionSide.RIGHT->1f;else->0f}
            dy=when(side){ConnectionSide.TOP->-1f;ConnectionSide.BOTTOM->1f;else->0f}
        }
        val len=maxOf(.001f,hypot(dx,dy))
        return PointF(p.x+dx/len*d,p.y+dy/len*d)
    }

    private fun offsetFromSide(p:PointF,side:ConnectionSide,d:Float):PointF=when(side){ConnectionSide.TOP->PointF(p.x,p.y-d);ConnectionSide.RIGHT->PointF(p.x+d,p.y);ConnectionSide.BOTTOM->PointF(p.x,p.y+d);ConnectionSide.LEFT->PointF(p.x-d,p.y);else->p}

    private fun visibilityRoute(start:PointF,end:PointF,obs:List<RouteObstacle>,bias:Int=0):List<PointF>{
        if(segmentClear(start,end,obs))return listOf(start,end)
        val nodes=mutableListOf<PointF>();nodes+=start;nodes+=end;obs.forEach{o->for(i in o.points.indices)nodes+=routeNodeForVertex(o.points,i,o.clearance+54f)}
        val n=nodes.size;val dist=FloatArray(n){Float.POSITIVE_INFINITY};val prev=IntArray(n){-1};val used=BooleanArray(n);dist[0]=0f
        repeat(n){var u=-1;var best=Float.POSITIVE_INFINITY;for(i in 0 until n)if(!used[i]&&dist[i]<best){best=dist[i];u=i};if(u<0)return@repeat;used[u]=true;for(v in 0 until n){if(used[v]||v==u||!segmentClear(nodes[u],nodes[v],obs))continue;val length=hypot(nodes[v].x-nodes[u].x,nodes[v].y-nodes[u].y);val bendPenalty=if(prev[u]>=0)turnPenalty(nodes[prev[u]],nodes[u],nodes[v]) else 0f;val sidePenalty=if(bias!=0&&prev[u]>=0&&abs(nodes[v].x-nodes[u].x)>abs(nodes[v].y-nodes[u].y)&&sign(nodes[v].x-nodes[u].x).toInt()!=bias)30f else 0f;val candidate=dist[u]+length+bendPenalty+sidePenalty;if(candidate<dist[v]){dist[v]=candidate;prev[v]=u}}}
        if(!dist[1].isFinite())return listOf(start,end)
        val result=mutableListOf<PointF>();var at=1;while(at>=0){result+=nodes[at];if(at==0)break;at=prev[at];if(at<0)return listOf(start,end)};result.reverse();return simplifyRoute(result,obs)
    }

    private fun gestureBias(points:List<PointF>):Int{
        if(points.size<2)return 0
        var left=0f
        var right=0f
        for(i in 1 until points.size){
            val dx=points[i].x-points[i-1].x
            if(abs(dx)>=abs(points[i].y-points[i-1].y)){
                if(dx<0)left+=-dx else right+=dx
            }
        }
        return when{
            left>right*1.35f->-1
            right>left*1.35f->1
            else->0
        }
    }

    private fun turnPenalty(a:PointF,b:PointF,c:PointF):Float{
        val abx=b.x-a.x;val aby=b.y-a.y
        val bcx=c.x-b.x;val bcy=c.y-b.y
        val ab=maxOf(.001f,hypot(abx,aby));val bc=maxOf(.001f,hypot(bcx,bcy))
        val dot=((abx*bcx+aby*bcy)/(ab*bc)).coerceIn(-1f,1f)
        return 150f*(1f-dot)
    }

    private fun sameDirection(a:PointF,b:PointF,c:PointF):Boolean{
        val abx=b.x-a.x;val aby=b.y-a.y
        val bcx=c.x-b.x;val bcy=c.y-b.y
        return (abs(abx)>=abs(aby))==(abs(bcx)>=abs(bcy)) &&
               (if(abs(abx)>=abs(aby)) sign(abx)==sign(bcx) else sign(aby)==sign(bcy))
    }

    private fun simplifyRoute(points:List<PointF>,obs:List<RouteObstacle>):List<PointF>{
        if(points.size<=2)return points
        val out=points.toMutableList();var changed=true
        while(changed&&out.size>2){changed=false;var i=1;while(i<out.lastIndex){if(segmentClear(out[i-1],out[i+1],obs)){out.removeAt(i);changed=true}else i++}}
        return out
    }

    fun beginCustomShapeMode(targetId:String){
        connectionMode=false; connectionStartId=null; connectionStartSide=null; customShapeMode=true; customShapeTargetId=targetId; customGesture.clear(); invalidate(); onSelectionChanged?.invoke()
    }
    fun finishCustomShapeMode(){
        customShapeMode=false; customShapeTargetId=null; customGesture.clear(); invalidate(); onSelectionChanged?.invoke()
    }
    fun cancelCustomShapeMode(){ customShapeMode=false; customShapeTargetId=null; customGesture.clear(); invalidate(); onSelectionChanged?.invoke() }
    fun commitCustomShape(){ if(!customShapeMode)return; val pts=customGesture.toList(); if(pts.size<8){ onSelectionChanged?.invoke(); return }; onCustomShapeFinished?.invoke(pts) }

    fun beginConnectionMode(){connectionMode=true;connectionStartId=null;connectionStartSide=null;selectedConnectionId=null;invalidate();onSelectionChanged?.invoke()}
    fun beginConnectionFrom(id:String){connectionMode=true;connectionStartId=id;connectionStartSide=null;selectedElementId=id;selectedConnectionId=null;invalidate();onSelectionChanged?.invoke()}
    fun finishConnectionMode(){connectionMode=false;connectionStartId=null;connectionStartSide=null;invalidate()}
    fun cancelConnectionMode(){connectionMode=false;connectionStartId=null;connectionStartSide=null;invalidate();onConnectionCancelled?.invoke();onSelectionChanged?.invoke()}

    override fun onTouchEvent(event:MotionEvent):Boolean{
        // Connect mode keeps point-to-point tapping, but canvas navigation remains enabled.
        // A stationary finger-up is a tap; a moving finger pans. Two fingers use the
        // same pinch/zoom + focus-pan gesture as normal canvas mode.
        if(connectionMode){
            scaleDetector.onTouchEvent(event)
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    gestureMoved=false
                    connectDownX=event.x; connectDownY=event.y
                    lastX=event.x; lastY=event.y
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN->{
                    if(event.pointerCount>=2){
                        gestureMoved=true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE->{
                    if(event.pointerCount>=2){
                        gestureMoved=true
                        return true
                    }
                    val dx=event.x-connectDownX; val dy=event.y-connectDownY
                    if(!gestureMoved && (dx*dx+dy*dy)>36f) gestureMoved=true
                    if(gestureMoved){
                        panX += event.x-lastX
                        panY += event.y-lastY
                    }
                    lastX=event.x; lastY=event.y
                    invalidate(); return true
                }
                MotionEvent.ACTION_POINTER_UP->{
                    if(event.pointerCount>1){
                        val remaining=if(event.actionIndex==0)1 else 0
                        if(remaining<event.pointerCount){
                            lastX=event.getX(remaining); lastY=event.getY(remaining)
                            connectDownX=lastX; connectDownY=lastY
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP->{
                    if(!gestureMoved){
                        val w=world(event.x,event.y)
                        val hit=connectionPointAt(w.x,w.y)
                        if(hit!=null){
                            val (element,side)=hit
                            if(connectionStartId==null){
                                connectionStartId=element.id; connectionStartSide=side
                                selectedElementId=element.id; selectedConnectionId=null
                            }else if(connectionStartSide==null && element.id==connectionStartId){
                                connectionStartSide=side
                            }else if(connectionStartSide!=null && element.id!=connectionStartId){
                                val from=document.elements.firstOrNull{it.id==connectionStartId}
                                if(from!=null){
                                    onConnectionRequested?.invoke(from.id,element.id,connectionStartSide!!,side,emptyList())
                                }
                            }
                            onSelectionChanged?.invoke(); invalidate()
                        }
                    }
                    gestureMoved=false
                    return true
                }
                MotionEvent.ACTION_CANCEL->{
                    gestureMoved=false; return true
                }
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                gestureMoved=false;dragMovedByGrid=false;lastX=event.x;lastY=event.y;val w=world(event.x,event.y)
                if(customShapeMode){ customGesture.clear(); customGesture.add(PointF(w.x,w.y)); gestureMoved=false; invalidate(); return true }
                val selected=selectedElement()
                if(selected!=null&&!customShapeMode){val h=handleAt(selected,w.x,w.y);if(h!=Handle.NONE){resizeId=selected.id;resizeHandle=h;dragId=null;pressElementId=null;pressConnectionId=null;startResize=RectF(selected.x,selected.y,selected.x+selected.width,selected.y+selected.height);return true};if(!lastNotesButton.isEmpty&&lastNotesButton.contains(w.x,w.y)){pressElementId=null;pressConnectionId=null;onNotesTap?.invoke(selected);return true}}
                val hit=hitElement(w.x,w.y)
                pressElementId=hit?.id;pressConnectionId=if(hit==null)hitConnection(w.x,w.y)?.id else null
                if(hit!=null){dragId=hit.id;dragOffsetX=w.x-hit.x;dragOffsetY=w.y-hit.y;startMoveX=hit.x;startMoveY=hit.y}
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN->{
                if(event.pointerCount>=2){dragId=null;resizeId=null;resizeHandle=Handle.NONE;gestureMoved=true}
                return true
            }
            MotionEvent.ACTION_POINTER_UP->{
                if(event.pointerCount>1){val remaining=if(event.actionIndex==0)1 else 0;if(remaining<event.pointerCount){lastX=event.getX(remaining);lastY=event.getY(remaining)};gestureMoved=true}
                return true
            }
            MotionEvent.ACTION_MOVE->{
                if(event.pointerCount>1){gestureMoved=true;return true}
                val w=world(event.x,event.y)
                if(customShapeMode){ customGesture.add(PointF(w.x,w.y)); gestureMoved=true; invalidate(); return true }
                if(resizeId!=null){resize(selectedElement()?:return true,w.x,w.y);gestureMoved=true}else if(dragId!=null){document.elements.firstOrNull{it.id==dragId}?.let{
                    val oldX=it.x;val oldY=it.y
                    it.x=w.x-dragOffsetX;it.y=w.y-dragOffsetY
                    it.x=round(it.x/gridSize)*gridSize;it.y=round(it.y/gridSize)*gridSize
                    if(it.x!=oldX||it.y!=oldY)dragMovedByGrid=true
                }}else{panX+=event.x-lastX;panY+=event.y-lastY;gestureMoved=true}
                lastX=event.x;lastY=event.y;invalidate();return true
            }
            MotionEvent.ACTION_UP->{
                if(customShapeMode){invalidate();return true}
                val dragged=if(dragId!=null)document.elements.firstOrNull{it.id==dragId} else null
                val wasTap=(dragId==null||!dragMovedByGrid)&&!gestureMoved
                if(wasTap){
                    if(pressElementId!=null){if(document.elements.any{it.id==pressElementId}){selectedElementId=pressElementId;selectedConnectionId=null}}
                    else if(pressConnectionId!=null){selectedElementId=null;selectedConnectionId=pressConnectionId}
                    else{selectedElementId=null;selectedConnectionId=null}
                    onSelectionChanged?.invoke();invalidate()
                }
                if(dragId!=null&&dragged!=null&&dragMovedByGrid)onMoveFinished?.invoke(dragged,startMoveX,startMoveY)
                val e=selectedElement();if(resizeId!=null&&e!=null){val old=startResize;if(old.left!=e.x||old.top!=e.y||old.width()!=e.width||old.height()!=e.height)onResizeFinished?.invoke(e,old.left,old.top,old.width(),old.height())}
                dragId=null;resizeId=null;resizeHandle=Handle.NONE;dragMovedByGrid=false;pressElementId=null;pressConnectionId=null;return true
            }
            MotionEvent.ACTION_CANCEL->{dragId=null;resizeId=null;resizeHandle=Handle.NONE;dragMovedByGrid=false;pressElementId=null;pressConnectionId=null;return true}
        }
        return true
    }

    private fun resize(e:FlowElement,x:Float,y:Float){var l=e.x;var t=e.y;var r=e.x+e.width;var b=e.y+e.height;val minW=70f;val minH=45f;when(resizeHandle){Handle.TL->{l=min(x,r-minW);t=min(y,b-minH)};Handle.T->{t=min(y,b-minH)};Handle.TR->{r=max(x,l+minW);t=min(y,b-minH)};Handle.L->{l=min(x,r-minW)};Handle.R->{r=max(x,l+minW)};Handle.BL->{l=min(x,r-minW);b=max(y,t+minH)};Handle.B->{b=max(y,t+minH)};Handle.BR->{r=max(x,l+minW);b=max(y,t+minH)};else->Unit};l=round(l/gridSize)*gridSize;t=round(t/gridSize)*gridSize;r=round(r/gridSize)*gridSize;b=round(b/gridSize)*gridSize;e.x=l;e.y=t;e.width=r-l;e.height=b-t}
    private fun explicitEndpoint(e:FlowElement,side:ConnectionSide):PointF = when(side){
        ConnectionSide.TOP->PointF(e.x+e.width/2f,e.y)
        ConnectionSide.RIGHT->PointF(e.x+e.width,e.y+e.height/2f)
        ConnectionSide.BOTTOM->PointF(e.x+e.width/2f,e.y+e.height)
        ConnectionSide.LEFT->PointF(e.x,e.y+e.height/2f)
        else->PointF(e.x+e.width/2f,e.y+e.height/2f)
    }
    private fun handlePoints(r:RectF)=listOf(PointF(r.left,r.top),PointF(r.centerX(),r.top),PointF(r.right,r.top),PointF(r.left,r.centerY()),PointF(r.right,r.centerY()),PointF(r.left,r.bottom),PointF(r.centerX(),r.bottom),PointF(r.right,r.bottom))
    private fun connectionPointAt(x:Float,y:Float):Pair<FlowElement,ConnectionSide>?{
        val threshold=maxOf(28f,30f/scale)
        val sides=listOf(
            ConnectionSide.TOP,ConnectionSide.RIGHT,ConnectionSide.BOTTOM,ConnectionSide.LEFT
        )
        for(e in document.elements.asReversed()){
            val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
            for(side in sides){
                // Connection-point hit testing follows the visible selection
                // box, just like the connection-point UI.
                val p=explicitEndpoint(e,side)
                if(hypot(x-p.x,y-p.y)<=threshold)return e to side
            }
        }
        return null
    }
    private fun handleAt(e: FlowElement, x: Float, y: Float): Handle {
        val r = RectF(e.x, e.y, e.x + e.width, e.y + e.height)
        val margin = maxOf(34f / scale, 22f)
        fun near(px: Float, py: Float): Boolean {
            return hypot(x - px, y - py) <= margin
        }
        if (near(r.left, r.top)) return Handle.TL
        if (near(r.right, r.top)) return Handle.TR
        if (near(r.left, r.bottom)) return Handle.BL
        if (near(r.right, r.bottom)) return Handle.BR
        if (abs(y - r.top) <= margin && x >= r.left - margin && x <= r.right + margin) return Handle.T
        if (abs(y - r.bottom) <= margin && x >= r.left - margin && x <= r.right + margin) return Handle.B
        if (abs(x - r.left) <= margin && y >= r.top - margin && y <= r.bottom + margin) return Handle.L
        if (abs(x - r.right) <= margin && y >= r.top - margin && y <= r.bottom + margin) return Handle.R
        return Handle.NONE
    }
    private fun world(x:Float,y:Float)=PointF((x-panX)/scale,(y-panY)/scale);private fun selectedElement()=document.elements.firstOrNull{it.id==selectedElementId};private fun hitElement(x:Float,y:Float)=document.elements.asReversed().firstOrNull{hitShape(it,x,y)};private fun hitShape(e:FlowElement,x:Float,y:Float):Boolean{val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height);return when(e.shape){ShapeType.DIAMOND->abs(x-r.centerX())/r.width()+abs(y-r.centerY())/r.height()<=.5f;else->r.contains(x,y)}}
    private fun hitConnection(x:Float,y:Float):FlowConnection?{val threshold=maxOf(22f,24f/scale);return document.connections.asReversed().firstOrNull{con->val a=document.elements.firstOrNull{it.id==con.fromId}?:return@firstOrNull false;val b=document.elements.firstOrNull{it.id==con.toId}?:return@firstOrNull false;val pair=document.connections.filter{(it.fromId==con.fromId&&it.toId==con.toId)||(it.fromId==con.toId&&it.toId==con.fromId)}.sortedBy{it.id};val idx=pair.indexOfFirst{it.id==con.id}.coerceAtLeast(0);val auto=connectionEndpoints(a,b,idx,pair.size);val p1=if(con.fromSide==ConnectionSide.AUTO)auto.first else faceEndpoint(a,con.fromSide,idx,pair.size,sideLength(b,con.toSide));val p2=if(con.toSide==ConnectionSide.AUTO)auto.second else faceEndpoint(b,con.toSide,idx,pair.size,sideLength(a,con.fromSide));val path=if(con.fromSide!=ConnectionSide.AUTO && con.toSide!=ConnectionSide.AUTO){buildDynamicRoutedPath(a,b,p1,p2,con.fromSide,con.toSide)}else buildConnectionPath(p1,p2,con.bendX,con.bendY,idx);val m=PathMeasure(path,false);val pos=FloatArray(2);var d=0f;while(d<=m.length){if(m.getPosTan(d,pos,null)&&hypot(x-pos[0],y-pos[1])<=threshold)return@firstOrNull true;d+=maxOf(6f,threshold/2f)};false}}
    private fun wrap(s:String,max:Int):List<String>{
        if(s.isEmpty())return listOf("")
        val out=mutableListOf<String>()
        s.replace("\r\n","\n").replace('\r','\n').split("\n", limit = Int.MAX_VALUE).forEach{paragraph->
            if(paragraph.length<=max){out+=paragraph;return@forEach}
            var rest=paragraph
            while(rest.length>max){
                val cut=rest.substring(0,max+1).lastIndexOf(' ').let{if(it>0)it else max}
                out+=rest.substring(0,cut)
                rest=if(cut<rest.length && rest[cut]==' ')rest.substring(cut+1)else rest.substring(cut)
            }
            out+=rest
        }
        return out
    }
    fun viewportStateJson(): String {
        return JSONObject().apply {
            put("scale", scale)
            put("panX", panX)
            put("panY", panY)
        }.toString()
    }

    fun restoreViewportState(raw: String) {
        if (raw.isBlank()) return
        runCatching {
            val o = JSONObject(raw)
            scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.25f, 5f)
            panX = o.optDouble("panX", 0.0).toFloat()
            panY = o.optDouble("panY", 0.0).toFloat()
            invalidate()
        }
    }

    fun resetViewport(){scale=1f;panX=0f;panY=0f;invalidate()}
    fun fitContent(){if(document.elements.isEmpty()){resetViewport();return};val minX=document.elements.minOf{it.x};val minY=document.elements.minOf{it.y};val maxX=document.elements.maxOf{it.x+it.width};val maxY=document.elements.maxOf{it.y+it.height};val pad=80f;val sx=width/(maxX-minX+pad*2);val sy=height/(maxY-minY+pad*2);scale=min(sx,sy).coerceIn(.25f,5f);panX=width/2f-(minX+(maxX-minX)/2f)*scale;panY=height/2f-(minY+(maxY-minY)/2f)*scale;invalidate()}
}
