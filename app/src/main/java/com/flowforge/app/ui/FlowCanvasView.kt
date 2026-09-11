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
    var onDoubleTapElement: ((FlowElement) -> Unit)? = null
    var onNotesTap: ((FlowElement) -> Unit)? = null
    var onConnectionRequested: ((String, String, ConnectionSide, ConnectionSide, List<PointF>) -> Unit)? = null
    var onConnectionCancelled: (() -> Unit)? = null
    var onCustomShapeFinished: ((List<PointF>) -> Unit)? = null
    var onMoveFinished: ((FlowElement, Float, Float) -> Unit)? = null
    var onResizeFinished: ((FlowElement, Float, Float, Float, Float) -> Unit)? = null
    var gridVisible = true
    var snapToGrid = true
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
    private var lastX = 0f; private var lastY = 0f; private var lastTap = 0L
    private var lastScaleFocusX = 0f; private var lastScaleFocusY = 0f
    private var dragId: String? = null; private var dragOffsetX = 0f; private var dragOffsetY = 0f
    private var startMoveX = 0f; private var startMoveY = 0f
    private var resizeId: String? = null; private var resizeHandle = Handle.NONE
    private var startResize = RectF(); private var lastNotesButton = RectF()
    private var gestureMoved = false
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

    private fun drawContent(c: Canvas, includeSelection: Boolean) {
        if (gridVisible) drawGrid(c)
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
            drawGrid(c)
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
        val p=Path();val cx=r.centerX();val cy=r.centerY();val outer=min(r.width(),r.height())*.5f;val inner=outer*.42f
        for(i in 0 until 10){val a=(-Math.PI/2.0)+(i*Math.PI/5.0);val rad=if(i%2==0)outer else inner;val x=cx+(kotlin.math.cos(a)*rad).toFloat();val y=cy+(kotlin.math.sin(a)*rad).toFloat();if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();return p
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

    private fun buildDynamicRoutedPath(
        a:FlowElement,
        b:FlowElement,
        start:PointF,
        end:PointF,
        fromSide:ConnectionSide,
        toSide:ConnectionSide,
    ):Path {
        // start/end are the actual automatically distributed attachment points
        // on the selected faces.
        val sourceOut=offsetFromSide(start,fromSide,64f)
        val targetOut=offsetFromSide(end,toSide,64f)
        val obstacles=obstacleRects(a.id,b.id)+listOf(
            expandedElementRect(a,48f),
            expandedElementRect(b,48f)
        )
        // Recalculate the route from the CURRENT block geometry every time the
        // canvas is drawn.  Stored routePoints are deliberately not used here: a
        // moved block must never leave an old elbow pinned to the canvas.
        val middle=visibilityRoute(sourceOut,targetOut,obstacles,0)
        val raw=mutableListOf<PointF>()
        raw+=start
        raw+=sourceOut
        raw.addAll(middle.drop(1).dropLast(1))
        raw+=targetOut
        raw+=end
        val cleaned=mutableListOf<PointF>()
        raw.forEach { if(cleaned.isEmpty() || hypot(it.x-cleaned.last().x,it.y-cleaned.last().y)>1f) cleaned+=it }
        return buildProtectedSmoothRoutePath(cleaned)
    }

    private fun buildProtectedSmoothRoutePath(points:List<PointF>):Path {
        val p=Path()
        if(points.isEmpty()) return p
        p.moveTo(points[0].x,points[0].y)
        if(points.size==2){
            p.lineTo(points[1].x,points[1].y)
            return p
        }

        // The first and last segments are deliberately kept straight.  They are
        // the protected "approach" segments: the connector may travel outside
        // the element's rectangular box, then cross that box only once it is on
        // the direct line to the real rendered outline.  This prevents smoothing
        // a corner from slicing across a star point, cloud lobe, document curl,
        // diamond corner, or another concave/irregular outline.
        p.lineTo(points[1].x,points[1].y)
        if(points.size==3){
            p.lineTo(points[2].x,points[2].y)
            return p
        }

        val radius=22f
        for(i in 2 until points.lastIndex-1){
            val prev=points[i-1]; val cur=points[i]; val next=points[i+1]
            val inLen=hypot(cur.x-prev.x,cur.y-prev.y)
            val outLen=hypot(next.x-cur.x,next.y-cur.y)
            if(inLen<1f || outLen<1f){ p.lineTo(cur.x,cur.y); continue }
            val r=min(radius,min(inLen,outLen)*.34f)
            val before=PointF(cur.x+(prev.x-cur.x)*r/inLen,cur.y+(prev.y-cur.y)*r/inLen)
            val after=PointF(cur.x+(next.x-cur.x)*r/outLen,cur.y+(next.y-cur.y)*r/outLen)
            p.lineTo(before.x,before.y)
            p.quadTo(cur.x,cur.y,after.x,after.y)
        }

        // Final approach is never rounded.  It is allowed to pass through the
        // bounding box only here, terminating exactly on the visible shape edge.
        p.lineTo(points[points.lastIndex-1].x,points[points.lastIndex-1].y)
        p.lineTo(points.last().x,points.last().y)
        return p
    }

    private fun buildRoutedPath(points:List<PointF>):Path{val p=Path();if(points.isEmpty())return p;if(points.size==1){p.moveTo(points[0].x,points[0].y);return p};val radius=28f;p.moveTo(points[0].x,points[0].y);for(i in 1 until points.lastIndex+1){val prev=points[i-1];val cur=points[i];val next=if(i<points.lastIndex)points[i+1]else null;if(next==null){p.lineTo(cur.x,cur.y);break};val inLen=hypot(cur.x-prev.x,cur.y-prev.y);val outLen=hypot(next.x-cur.x,next.y-cur.y);if(inLen<1f||outLen<1f){p.lineTo(cur.x,cur.y);continue};val r=min(radius,min(inLen,outLen)*.38f);val before=PointF(cur.x+(prev.x-cur.x)*r/inLen,cur.y+(prev.y-cur.y)*r/inLen);val after=PointF(cur.x+(next.x-cur.x)*r/outLen,cur.y+(next.y-cur.y)*r/outLen);p.lineTo(before.x,before.y);p.quadTo(cur.x,cur.y,after.x,after.y)};return p}
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
    private fun obstacleRects(a:String,b:String):List<RectF> =
        document.elements
            .filter { it.id != a && it.id != b }
            .map { RectF(it.x - 40f, it.y - 40f, it.x + it.width + 40f, it.y + it.height + 40f) }

    private fun expandedElementRect(e:FlowElement, margin:Float = 40f):RectF =
        RectF(e.x - margin, e.y - margin, e.x + e.width + margin, e.y + e.height + margin)

    private fun segmentClear(a:PointF,b:PointF,obs:List<RectF>):Boolean {
        fun pointIn(r:RectF,p:PointF)=p.x>r.left && p.x<r.right && p.y>r.top && p.y<r.bottom
        fun cross(a:PointF,b:PointF,c:PointF)=
            (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
        fun onSegment(a:PointF,b:PointF,p:PointF)=
            abs(cross(a,b,p))<0.01f &&
            p.x>=min(a.x,b.x)-0.01f && p.x<=max(a.x,b.x)+0.01f &&
            p.y>=min(a.y,b.y)-0.01f && p.y<=max(a.y,b.y)+0.01f
        fun intersects(a:PointF,b:PointF,c:PointF,d:PointF):Boolean {
            val ab1=cross(a,b,c);val ab2=cross(a,b,d)
            val cd1=cross(c,d,a);val cd2=cross(c,d,b)
            if(((ab1>0f&&ab2<0f)||(ab1<0f&&ab2>0f)) &&
               ((cd1>0f&&cd2<0f)||(cd1<0f&&cd2>0f))) return true
            return onSegment(a,b,c)||onSegment(a,b,d)||onSegment(c,d,a)||onSegment(c,d,b)
        }
        for(r in obs){
            if(pointIn(r,a)||pointIn(r,b)) return false
            val tl=PointF(r.left,r.top);val tr=PointF(r.right,r.top)
            val br=PointF(r.right,r.bottom);val bl=PointF(r.left,r.bottom)
            if(intersects(a,b,tl,tr)||intersects(a,b,tr,br)||intersects(a,b,br,bl)||intersects(a,b,bl,tl)) return false
        }
        return true
    }

    private fun routeConnection(a:FlowElement,b:FlowElement,fromSide:ConnectionSide,toSide:ConnectionSide,gesture:List<PointF>):List<PointF>{
        val start=shapeBoundaryEndpoint(a,fromSide,0f)
        val end=shapeBoundaryEndpoint(b,toSide,0f)
        val sourceOut=offsetFromSide(start,fromSide,64f)
        val targetOut=offsetFromSide(end,toSide,64f)

        // The finger path is intentionally used only to choose the two faces.
        // Once the finger is released, the connector is regenerated cleanly so
        // accidental wiggles never become ugly permanent routing waypoints.
        val obstacles=obstacleRects(a.id,b.id)+listOf(
            expandedElementRect(a,48f),
            expandedElementRect(b,48f)
        )

        val middle=visibilityRoute(sourceOut,targetOut,obstacles,gestureBias(gesture))
        val raw=mutableListOf<PointF>()
        raw+=start
        raw+=sourceOut
        raw.addAll(middle.drop(1).dropLast(1))
        raw+=targetOut
        raw+=end

        val cleaned=mutableListOf<PointF>()
        for(pt in raw){
            if(cleaned.isEmpty() || hypot(pt.x-cleaned.last().x,pt.y-cleaned.last().y)>1f) cleaned+=pt
        }
        return if(cleaned.size>=2) cleaned else listOf(start,end)
    }

    private fun offsetFromSide(p:PointF,side:ConnectionSide,d:Float):PointF = when(side){
        ConnectionSide.TOP->PointF(p.x,p.y-d)
        ConnectionSide.RIGHT->PointF(p.x+d,p.y)
        ConnectionSide.BOTTOM->PointF(p.x,p.y+d)
        ConnectionSide.LEFT->PointF(p.x-d,p.y)
        else->p
    }

    /**
     * Finds a short, obstacle-free polyline using obstacle corners as visibility
     * nodes. Unlike a square grid, this does not manufacture dozens of tiny
     * horizontal/vertical steps, so the final smoothed connector stays elegant.
     */
    private fun visibilityRoute(start:PointF,end:PointF,obs:List<RectF>,bias:Int=0):List<PointF>{
        if(segmentClear(start,end,obs)) return listOf(start,end)

        val nodes=mutableListOf<PointF>()
        nodes+=start
        nodes+=end
        obs.forEach { r ->
            val gap=10f
            nodes+=PointF(r.left-gap,r.top-gap)
            nodes+=PointF(r.right+gap,r.top-gap)
            nodes+=PointF(r.right+gap,r.bottom+gap)
            nodes+=PointF(r.left-gap,r.bottom+gap)
        }

        val n=nodes.size
        val dist=FloatArray(n){Float.POSITIVE_INFINITY}
        val prev=IntArray(n){-1}
        val used=BooleanArray(n)
        dist[0]=0f

        repeat(n){
            var u=-1
            var best=Float.POSITIVE_INFINITY
            for(i in 0 until n){
                if(!used[i] && dist[i]<best){best=dist[i];u=i}
            }
            if(u<0)return@repeat
            used[u]=true
            for(v in 0 until n){
                if(used[v] || v==u)continue
                if(!segmentClear(nodes[u],nodes[v],obs))continue
                val length=hypot(nodes[v].x-nodes[u].x,nodes[v].y-nodes[u].y)
                val bendPenalty=if(prev[u]>=0 && !sameDirection(nodes[prev[u]],nodes[u],nodes[v])) 18f else 0f
                val sidePenalty=if(bias!=0 && prev[u]>=0 && abs(nodes[v].x-nodes[u].x)>abs(nodes[v].y-nodes[u].y) && sign(nodes[v].x-nodes[u].x).toInt()!=bias) 90f else 0f
                val candidate=dist[u]+length+bendPenalty+sidePenalty
                if(candidate<dist[v]){dist[v]=candidate;prev[v]=u}
            }
        }

        if(!dist[1].isFinite()) return listOf(start,end)
        val result=mutableListOf<PointF>()
        var at=1
        while(at>=0){
            result+=nodes[at]
            if(at==0)break
            at=prev[at]
            if(at<0)return listOf(start,end)
        }
        result.reverse()
        return simplifyRoute(result,obs)
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

    private fun sameDirection(a:PointF,b:PointF,c:PointF):Boolean{
        val abx=b.x-a.x;val aby=b.y-a.y
        val bcx=c.x-b.x;val bcy=c.y-b.y
        return (abs(abx)>=abs(aby))==(abs(bcx)>=abs(bcy)) &&
               (if(abs(abx)>=abs(aby)) sign(abx)==sign(bcx) else sign(aby)==sign(bcy))
    }

    private fun simplifyRoute(points:List<PointF>, obs:List<RectF>):List<PointF>{
        if(points.size<=2)return points
        val out=points.toMutableList()
        var changed=true
        while(changed && out.size>2){
            changed=false
            var i=1
            while(i<out.lastIndex){
                if(segmentClear(out[i-1],out[i+1],obs)){
                    out.removeAt(i)
                    changed=true
                }else i++
            }
        }
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
                gestureMoved=false;lastX=event.x;lastY=event.y;val w=world(event.x,event.y)
                if(customShapeMode){ customGesture.clear(); customGesture.add(PointF(w.x,w.y)); gestureMoved=false; invalidate(); return true }
                val selected=selectedElement()
                if(selected!=null&&!customShapeMode){val h=handleAt(selected,w.x,w.y);if(h!=Handle.NONE){resizeId=selected.id;resizeHandle=h;dragId=null;startResize=RectF(selected.x,selected.y,selected.x+selected.width,selected.y+selected.height);return true};if(!lastNotesButton.isEmpty&&lastNotesButton.contains(w.x,w.y)){onNotesTap?.invoke(selected);return true}}
                val hit=hitElement(w.x,w.y)
                if(hit!=null){selectedElementId=hit.id;selectedConnectionId=null;dragId=hit.id;dragOffsetX=w.x-hit.x;dragOffsetY=w.y-hit.y;startMoveX=hit.x;startMoveY=hit.y;val now=System.currentTimeMillis();if(now-lastTap<300)onDoubleTapElement?.invoke(hit);lastTap=now}else{selectedElementId=null;selectedConnectionId=hitConnection(w.x,w.y)?.id}
                onSelectionChanged?.invoke();invalidate();return true
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
                if(resizeId!=null){resize(selectedElement()?:return true,w.x,w.y);gestureMoved=true}else if(dragId!=null){selectedElement()?.let{it.x=w.x-dragOffsetX;it.y=w.y-dragOffsetY;if(snapToGrid){it.x=round(it.x/gridSize)*gridSize;it.y=round(it.y/gridSize)*gridSize}};gestureMoved=true}else{panX+=event.x-lastX;panY+=event.y-lastY;gestureMoved=true}
                lastX=event.x;lastY=event.y;invalidate();return true
            }
            MotionEvent.ACTION_UP->{
                if(customShapeMode){invalidate();return true}
                val e=selectedElement();if(dragId!=null&&e!=null&&(e.x!=startMoveX||e.y!=startMoveY))onMoveFinished?.invoke(e,startMoveX,startMoveY)
                if(resizeId!=null&&e!=null){val old=startResize;if(old.left!=e.x||old.top!=e.y||old.width()!=e.width||old.height()!=e.height)onResizeFinished?.invoke(e,old.left,old.top,old.width(),old.height())}
                dragId=null;resizeId=null;resizeHandle=Handle.NONE;return true
            }
            MotionEvent.ACTION_CANCEL->{dragId=null;resizeId=null;resizeHandle=Handle.NONE;return true}
        }
        return true
    }

    private fun resize(e:FlowElement,x:Float,y:Float){var l=e.x;var t=e.y;var r=e.x+e.width;var b=e.y+e.height;val minW=70f;val minH=45f;when(resizeHandle){Handle.TL->{l=min(x,r-minW);t=min(y,b-minH)};Handle.T->{t=min(y,b-minH)};Handle.TR->{r=max(x,l+minW);t=min(y,b-minH)};Handle.L->{l=min(x,r-minW)};Handle.R->{r=max(x,l+minW)};Handle.BL->{l=min(x,r-minW);b=max(y,t+minH)};Handle.B->{b=max(y,t+minH)};Handle.BR->{r=max(x,l+minW);b=max(y,t+minH)};else->Unit};if(snapToGrid){l=round(l/gridSize)*gridSize;t=round(t/gridSize)*gridSize;r=round(r/gridSize)*gridSize;b=round(b/gridSize)*gridSize};e.x=l;e.y=t;e.width=r-l;e.height=b-t}
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
