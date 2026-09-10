package com.flowforge.app.ui

import android.content.Context
import android.graphics.*
import android.view.*
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
    var onMoveFinished: ((FlowElement, Float, Float) -> Unit)? = null
    var onResizeFinished: ((FlowElement, Float, Float, Float, Float) -> Unit)? = null
    var gridVisible = true
    var snapToGrid = true
    var gridSize = 40f
    var darkMode = false
    var connectionMode = false
        private set
    private var connectionStartId: String? = null
    private var connectionPreview = PointF()
    private var connectionStartPoint = PointF()
    private val connectionGesture = mutableListOf<PointF>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f; private var panX = 0f; private var panY = 0f
    private var lastX = 0f; private var lastY = 0f; private var lastTap = 0L
    private var dragId: String? = null; private var dragOffsetX = 0f; private var dragOffsetY = 0f
    private var startMoveX = 0f; private var startMoveY = 0f
    private var resizeId: String? = null; private var resizeHandle = Handle.NONE
    private var startResize = RectF(); private var lastNotesButton = RectF()
    private var gestureMoved = false

    private enum class Handle { NONE, TL, T, TR, L, R, BL, B, BR }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * detector.scaleFactor).coerceIn(0.25f, 5f)
            val fx = detector.focusX; val fy = detector.focusY
            panX = fx - (fx - panX) * (scale / old); panY = fy - (fy - panY) * (scale / old)
            gestureMoved = true; invalidate(); return true
        }
    })

    override fun onDraw(c: Canvas) {
        c.drawColor(if (darkMode) Color.rgb(15,23,42) else Color.WHITE)
        c.save(); c.translate(panX, panY); c.scale(scale, scale)
        drawContent(c, includeSelection = true)
        c.restore()
    }

    fun drawContentForExport(c: Canvas) { drawContent(c, includeSelection = false) }

    private fun drawContent(c: Canvas, includeSelection: Boolean) {
        if (gridVisible) drawGrid(c)
        document.connections.forEach { drawConnection(c, it) }
        document.elements.forEach { drawElement(c, it) }
        if (connectionMode && connectionStartId != null) drawConnectionPreview(c)
        if (includeSelection) selectedElement()?.let { drawSelection(c, it) }
    }

    private fun drawGrid(c: Canvas) {
        gridPaint.color = if (darkMode) 0x405b7088 else 0x30475a6b
        gridPaint.strokeWidth = 1f
        val left = floor((-panX / scale) / gridSize).toInt() * gridSize
        val top = floor((-panY / scale) / gridSize).toInt() * gridSize
        val right = ((width - panX) / scale) + gridSize
        val bottom = ((height - panY) / scale) + gridSize
        var x = left; while (x <= right) { c.drawLine(x, top, x, bottom, gridPaint); x += gridSize }
        var y = top; while (y <= bottom) { c.drawLine(left, y, right, y, gridPaint); y += gridSize }
    }

    private fun outlineWidth(e: FlowElement): Float = when (e.outlineThickness) {
        LineThickness.DEFAULT -> 2.5f
        LineThickness.MEDIUM -> 7.5f
        LineThickness.LARGE -> 15f
    }

    private fun connectionWidth(c: FlowConnection): Float = when (c.thickness) {
        LineThickness.DEFAULT -> 3.5f
        LineThickness.MEDIUM -> 7f
        LineThickness.LARGE -> 14f
    }

    private fun drawElement(c: Canvas, e: FlowElement) {
        val r = RectF(e.x, e.y, e.x + e.width, e.y + e.height)
        if (e.fillColor != null) {
            paint.style = Paint.Style.FILL
            paint.color = e.fillColor!!
            drawShape(c, e, r)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (e.id == selectedElementId) maxOf(5f, outlineWidth(e)) else outlineWidth(e)
        paint.color = if (e.id == selectedElementId) 0xff2563eb.toInt() else (e.outlineColor ?: if (darkMode) 0xff94a3b8.toInt() else 0xff334155.toInt())
        drawShape(c, e, r)
        textPaint.color = if (darkMode) Color.WHITE else 0xff172033.toInt(); textPaint.textSize = 25f
        val maxChars = max(8, (e.width / 15f).toInt()); val lines = wrap(e.label, maxChars).take(4)
        val lineH = 29f; val base = e.y + e.height / 2f - (lines.size - 1) * lineH / 2f + 9f
        lines.forEachIndexed { i, s -> c.drawText(s, e.x + e.width/2f - textPaint.measureText(s)/2f, base + i*lineH, textPaint) }
        if (e.notes.isNotBlank()) drawBadge(c, e.x + e.width - 14f, e.y + 14f, true)
    }

    private fun drawShape(c: Canvas, e: FlowElement, r: RectF) {
        when (e.shape) {
            ShapeType.RECTANGLE -> c.drawRect(r, paint)
            ShapeType.ROUNDED -> c.drawRoundRect(r, 18f, 18f, paint)
            ShapeType.DIAMOND -> c.drawPath(Path().apply { moveTo(r.centerX(), r.top); lineTo(r.right, r.centerY()); lineTo(r.centerX(), r.bottom); lineTo(r.left, r.centerY()); close() }, paint)
            ShapeType.OVAL -> c.drawOval(r, paint)
            ShapeType.PARALLELOGRAM -> c.drawPath(Path().apply { val s=min(25f,r.width()*0.18f); moveTo(r.left+s,r.top); lineTo(r.right,r.top); lineTo(r.right-s,r.bottom); lineTo(r.left,r.bottom); close() }, paint)
            ShapeType.CYLINDER -> { val ry=min(18f,r.height()/5f); c.drawRoundRect(r, ry, ry, paint) }
            ShapeType.DOCUMENT -> c.drawPath(Path().apply { moveTo(r.left,r.top); lineTo(r.right,r.top); lineTo(r.right,r.bottom-14); quadTo(r.centerX(),r.bottom+10,r.left,r.bottom-14); close() }, paint)
            ShapeType.HEXAGON -> c.drawPath(Path().apply { val s=min(r.width()*0.18f,r.height()*0.35f); moveTo(r.left+s,r.top); lineTo(r.right-s,r.top); lineTo(r.right,r.centerY()); lineTo(r.right-s,r.bottom); lineTo(r.left+s,r.bottom); lineTo(r.left,r.centerY()); close() }, paint)
            ShapeType.CLOUD -> c.drawPath(Path().apply { addOval(RectF(r.left,r.top+r.height()*0.2f,r.left+r.width()*0.55f,r.bottom), Path.Direction.CW); addOval(RectF(r.left+r.width()*0.28f,r.top,r.right-r.width()*0.18f,r.bottom), Path.Direction.CW); addOval(RectF(r.right-r.width()*0.48f,r.top+r.height()*0.18f,r.right,r.bottom), Path.Direction.CW); close() }, paint)
            ShapeType.CIRCLE -> c.drawOval(r, paint)
        }
    }

    private fun drawSelection(c: Canvas, e: FlowElement) {
        val r = RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=0xff2563eb.toInt()
        c.drawRect(r,paint)
        val hs=10f
        handlePoints(r).forEach { p -> paint.style=Paint.Style.FILL; paint.color=Color.WHITE; c.drawCircle(p.x,p.y,hs,paint); paint.style=Paint.Style.STROKE; paint.color=0xff2563eb.toInt(); paint.strokeWidth=3f; c.drawCircle(p.x,p.y,hs,paint) }
        if(e.notes.isNotBlank()){ lastNotesButton=RectF(r.right-30f,r.top-30f,r.right+2f,r.top+2f) } else lastNotesButton.setEmpty()
    }

    private fun drawBadge(c:Canvas,x:Float,y:Float,info:Boolean){ paint.style=Paint.Style.FILL;paint.color=0xfff59e0b.toInt();c.drawCircle(x,y,10f,paint);textPaint.color=Color.WHITE;textPaint.textSize=13f;c.drawText(if(info)"i" else "!",x-2.3f,y+4.5f,textPaint) }

    private fun drawConnection(c: Canvas, con: FlowConnection) {
        val a=document.elements.firstOrNull{it.id==con.fromId} ?: return
        val b=document.elements.firstOrNull{it.id==con.toId} ?: return
        val pair=document.connections.filter{(it.fromId==con.fromId && it.toId==con.toId)||(it.fromId==con.toId && it.toId==con.fromId)}.sortedBy{it.id}
        val pairIndex=pair.indexOfFirst{it.id==con.id}.coerceAtLeast(0)
        val autoEndpoints=connectionEndpoints(a,b,pairIndex,pair.size)
        val p1=if(con.fromSide==ConnectionSide.AUTO)autoEndpoints.first else explicitEndpoint(a,con.fromSide)
        val p2=if(con.toSide==ConnectionSide.AUTO)autoEndpoints.second else explicitEndpoint(b,con.toSide)
        val path = if (con.routePoints.size >= 2) {
            val route = con.routePoints.map { PointF(it.x, it.y) }.toMutableList()
            route[0] = p1; route[route.lastIndex] = p2
            buildRoutedPath(route)
        } else {
            buildConnectionPath(p1,p2,con.bendX,con.bendY,pairIndex)
        }
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=if(con.id==selectedConnectionId)7f else connectionWidth(con)
        paint.color=if(con.id==selectedConnectionId)0xff2563eb.toInt() else con.color
        paint.pathEffect=when(con.lineStyle){LineStyle.DASHED->DashPathEffect(floatArrayOf(18f,12f),0f);LineStyle.DOTTED->DashPathEffect(floatArrayOf(4f,10f),0f);else->null}
        c.drawPath(path,paint);paint.pathEffect=null
        if(con.arrowType==ArrowType.REPEATED) drawRepeatedArrows(c,path)
        else if(con.arrowType!=ArrowType.NONE) drawConnectionArrows(c,path,con.arrowType)
        val mid=pathMidpoint(path)
        if(con.label.isNotBlank()){textPaint.color=if(darkMode)Color.WHITE else 0xff334155.toInt();textPaint.textSize=21f;c.drawText(con.label,mid.x+6,mid.y-6,textPaint)}
        if(con.notes.isNotBlank()) drawBadge(c,mid.x+12,mid.y-18,false)
    }

    private fun preferredSide(a:FlowElement,b:FlowElement):ConnectionSide {
        val dx=(b.x+b.width/2f)-(a.x+a.width/2f); val dy=(b.y+b.height/2f)-(a.y+a.height/2f)
        return if(abs(dy)>=abs(dx)) { if(dy>=0) ConnectionSide.BOTTOM else ConnectionSide.TOP } else { if(dx>=0) ConnectionSide.RIGHT else ConnectionSide.LEFT }
    }
    private fun distributedSide(preferred:ConnectionSide,index:Int):ConnectionSide {
        if(preferred==ConnectionSide.AUTO)return ConnectionSide.AUTO
        val order=when(preferred){
            ConnectionSide.TOP->arrayOf(ConnectionSide.TOP,ConnectionSide.RIGHT,ConnectionSide.LEFT,ConnectionSide.BOTTOM)
            ConnectionSide.RIGHT->arrayOf(ConnectionSide.RIGHT,ConnectionSide.BOTTOM,ConnectionSide.TOP,ConnectionSide.LEFT)
            ConnectionSide.BOTTOM->arrayOf(ConnectionSide.BOTTOM,ConnectionSide.LEFT,ConnectionSide.RIGHT,ConnectionSide.TOP)
            ConnectionSide.LEFT->arrayOf(ConnectionSide.LEFT,ConnectionSide.TOP,ConnectionSide.BOTTOM,ConnectionSide.RIGHT)
            else->arrayOf(ConnectionSide.TOP)
        }
        return order[index % order.size]
    }
    private fun connectionEndpoints(a:FlowElement,b:FlowElement,index:Int,count:Int):Pair<PointF,PointF>{
        val preferred=preferredSide(a,b)
        val sideA=distributedSide(if(a == b) ConnectionSide.BOTTOM else preferred,index)
        val sideB=when(sideA){ConnectionSide.TOP->ConnectionSide.BOTTOM;ConnectionSide.RIGHT->ConnectionSide.LEFT;ConnectionSide.BOTTOM->ConnectionSide.TOP;ConnectionSide.LEFT->ConnectionSide.RIGHT;else->ConnectionSide.AUTO}
        fun point(e:FlowElement,side:ConnectionSide,offset:Float):PointF=when(side){
            ConnectionSide.TOP->PointF((e.x+e.width/2f+offset).coerceIn(e.x+8f,e.x+e.width-8f),e.y)
            ConnectionSide.RIGHT->PointF(e.x+e.width,(e.y+e.height/2f+offset).coerceIn(e.y+8f,e.y+e.height-8f))
            ConnectionSide.BOTTOM->PointF((e.x+e.width/2f+offset).coerceIn(e.x+8f,e.x+e.width-8f),e.y+e.height)
            ConnectionSide.LEFT->PointF(e.x,(e.y+e.height/2f+offset).coerceIn(e.y+8f,e.y+e.height-8f))
            else->PointF(e.x+e.width/2f,e.y+e.height/2f)
        }
        val lane=if(count<=1)0f else ((index-(count-1)/2f)*minOf(a.width,a.height)*0.28f).coerceIn(-minOf(a.width,a.height)*0.42f,minOf(a.width,a.height)*0.42f)
        return point(a,sideA,lane) to point(b,sideB,lane)
    }
    private fun explicitEndpoint(e:FlowElement,side:ConnectionSide):PointF=when(side){
        ConnectionSide.TOP->PointF(e.x+e.width/2f,e.y)
        ConnectionSide.RIGHT->PointF(e.x+e.width,e.y+e.height/2f)
        ConnectionSide.BOTTOM->PointF(e.x+e.width/2f,e.y+e.height)
        ConnectionSide.LEFT->PointF(e.x,e.y+e.height/2f)
        else->PointF(e.x+e.width/2f,e.y+e.height/2f)
    }
    private fun endpointSide(e:FlowElement,p:PointF):ConnectionSide {
        val dl=abs(p.x-e.x); val dr=abs(p.x-(e.x+e.width)); val dt=abs(p.y-e.y); val db=abs(p.y-(e.y+e.height))
        return when(minOf(dl,dr,dt,db)){dt->ConnectionSide.TOP;dr->ConnectionSide.RIGHT;db->ConnectionSide.BOTTOM;else->ConnectionSide.LEFT}
    }

    private fun buildConnectionPath(p1:PointF,p2:PointF,bx:Float,by:Float,index:Int):Path{
        val p=Path();p.moveTo(p1.x,p1.y)
        val dx=p2.x-p1.x; val dy=p2.y-p1.y
        if(bx!=0f||by!=0f){p.quadTo((p1.x+p2.x)/2f+bx,(p1.y+p2.y)/2f+by,p2.x,p2.y)}
        else if(abs(dy)>=abs(dx)){val mid=(p1.y+p2.y)/2f + if(index%2==0) 0f else 18f;p.cubicTo(p1.x,mid,p2.x,mid,p2.x,p2.y)}
        else{val mid=(p1.x+p2.x)/2f + if(index%2==0) 0f else 18f;p.cubicTo(mid,p1.y,mid,p2.y,p2.x,p2.y)}
        return p
    }

    private fun buildRoutedPath(points:List<PointF>):Path{
        val p=Path();if(points.isEmpty())return p;if(points.size==1){p.moveTo(points[0].x,points[0].y);return p}
        val radius=22f
        p.moveTo(points[0].x,points[0].y)
        for(i in 1 until points.lastIndex+1){
            val prev=points[i-1];val cur=points[i];val next=if(i<points.lastIndex)points[i+1] else null
            if(next==null){p.lineTo(cur.x,cur.y);break}
            val inLen=hypot(cur.x-prev.x,cur.y-prev.y);val outLen=hypot(next.x-cur.x,next.y-cur.y)
            if(inLen<1f||outLen<1f){p.lineTo(cur.x,cur.y);continue}
            val r=min(radius,min(inLen,outLen)*0.32f)
            val before=PointF(cur.x+(prev.x-cur.x)*r/inLen,cur.y+(prev.y-cur.y)*r/inLen)
            val after=PointF(cur.x+(next.x-cur.x)*r/outLen,cur.y+(next.y-cur.y)*r/outLen)
            p.lineTo(before.x,before.y);p.quadTo(cur.x,cur.y,after.x,after.y)
        }
        return p
    }

    private fun pathMidpoint(path:Path):PointF{
        val m=PathMeasure(path,false);if(m.length<=0f)return PointF()
        val pos=FloatArray(2);m.getPosTan(m.length/2f,pos,null);return PointF(pos[0],pos[1])
    }

    private fun drawConnectionArrows(c:Canvas,path:Path,type:ArrowType){
        val m=PathMeasure(path,false);if(m.length<=1f)return
        val pos=FloatArray(2);val tan=FloatArray(2)
        fun sample(distance:Float):Pair<PointF,PointF>{m.getPosTan(distance.coerceIn(0f,m.length),pos,tan);return PointF(pos[0],pos[1]) to PointF(tan[0],tan[1])}
        val end=sample(m.length);val start=sample(min(20f,m.length))
        when(type){
            ArrowType.END->drawArrow(c,end.first.x-end.second.x*8f,end.first.y-end.second.y*8f,end.first.x,end.first.y,ArrowType.END)
            ArrowType.BOTH->{drawArrow(c,end.first.x-end.second.x*8f,end.first.y-end.second.y*8f,end.first.x,end.first.y,ArrowType.END);drawArrow(c,start.first.x+start.second.x*8f,start.first.y+start.second.y*8f,start.first.x,start.first.y,ArrowType.END)}
            ArrowType.CIRCLE->{paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;c.drawCircle(end.first.x,end.first.y,7f,paint)}
            ArrowType.DIAMOND->drawArrow(c,end.first.x-end.second.x*8f,end.first.y-end.second.y*8f,end.first.x,end.first.y,ArrowType.DIAMOND)
            else->Unit
        }
    }

    private fun simplifyGesture(points:List<PointF>):List<PointF>{
        if(points.size<2)return points.toList()
        val out=mutableListOf<PointF>();out+=points.first();var last=points.first();var lastDx=0f;var lastDy=0f
        for(i in 1 until points.lastIndex){
            val cur=points[i];val dx=cur.x-last.x;val dy=cur.y-last.y
            if(hypot(dx,dy)<10f)continue
            val len=hypot(dx,dy);val ndx=dx/len;val ndy=dy/len
            if(out.size==1||abs(ndx-lastDx)+abs(ndy-lastDy)>0.35f||hypot(dx,dy)>70f){out+=PointF(cur.x,cur.y);last=cur;lastDx=ndx;lastDy=ndy}
        }
        out+=points.last();return out.take(9)
    }

    private fun obstacleRects(excludeA:String,excludeB:String):List<RectF>{
        val margin=24f
        return document.elements.filter{it.id!=excludeA&&it.id!=excludeB}.map{RectF(it.x-margin,it.y-margin,it.x+it.width+margin,it.y+it.height+margin)}
    }

    private fun segmentClear(a:PointF,b:PointF,obstacles:List<RectF>):Boolean{
        val steps=max(2,(hypot(b.x-a.x,b.y-a.y)/10f).toInt())
        for(i in 0..steps){val t=i.toFloat()/steps;val x=a.x+(b.x-a.x)*t;val y=a.y+(b.y-a.y)*t;if(obstacles.any{it.contains(x,y)})return false}
        return true
    }

    private fun routeSegment(start:PointF,end:PointF,obstacles:List<RectF>,hints:List<PointF>):List<PointF>{
        if(segmentClear(start,end,obstacles))return listOf(start,end)
        val cell=max(20f,min(32f,gridSize/1.5f));val margin=240f
        val xs=mutableListOf(start.x,end.x);val ys=mutableListOf(start.y,end.y)
        obstacles.forEach{xs+=it.left;xs+=it.right;ys+=it.top;ys+=it.bottom};hints.forEach{xs+=it.x;ys+=it.y}
        val minX=floor((xs.minOrNull()!!-margin)/cell)*cell
        val maxX=ceil((xs.maxOrNull()!!+margin)/cell)*cell
        val minY=floor((ys.minOrNull()!!-margin)/cell)*cell
        val maxY=ceil((ys.maxOrNull()!!+margin)/cell)*cell
        fun cellPoint(k:Pair<Int,Int>)=PointF(minX+k.first*cell,minY+k.second*cell)
        fun cellKey(p:PointF)=Pair(round((p.x-minX)/cell).toInt(),round((p.y-minY)/cell).toInt())
        val s=cellKey(start);val g=cellKey(end);val maxIx=round((maxX-minX)/cell).toInt();val maxIy=round((maxY-minY)/cell).toInt()
        fun blocked(k:Pair<Int,Int>):Boolean{if(k==s||k==g)return false;val p=cellPoint(k);return obstacles.any{it.contains(p.x,p.y)}}
        val came=HashMap<Pair<Int,Int>,Pair<Int,Int>>();val gScore=HashMap<Pair<Int,Int>,Float>();val fScore=HashMap<Pair<Int,Int>,Float>()
        val open=java.util.PriorityQueue<Pair<Int,Int>>(compareBy{fScore[it]?:Float.POSITIVE_INFINITY})
        gScore[s]=0f;fScore[s]=heuristic(s,g);open.add(s)
        val dirs=arrayOf(Pair(1,0),Pair(-1,0),Pair(0,1),Pair(0,-1))
        var found=false;var guard=0
        while(open.isNotEmpty()&&guard++<12000){
            val cur=open.poll();if(cur==g){found=true;break}
            for(d in dirs){
                val n=Pair(cur.first+d.first,cur.second+d.second)
                if(n.first<0||n.second<0||n.first>maxIx||n.second>maxIy||blocked(n))continue
                val prev=came[cur];val bend=if(prev!=null&&prev.first!=cur.first&&prev.second!=cur.second)5f else 0f
                val p=cellPoint(n);val hintPenalty=if(hints.isEmpty())0f else hints.minOf{hypot(p.x-it.x,p.y-it.y)}*0.012f
                val tentative=(gScore[cur]?:Float.POSITIVE_INFINITY)+1f+bend+hintPenalty
                if(tentative<(gScore[n]?:Float.POSITIVE_INFINITY)){came[n]=cur;gScore[n]=tentative;fScore[n]=tentative+heuristic(n,g);open.add(n)}
            }
        }
        if(!found)return listOf(start,end)
        val cells=mutableListOf<Pair<Int,Int>>();var cur=g;cells+=cur
        while(cur!=s){cur=came[cur]?:break;cells+=cur};cells.reverse()
        val pts=cells.map{cellPoint(it)}.toMutableList();if(pts.isNotEmpty()){pts[0]=start;pts[pts.lastIndex]=end}
        val simplified=mutableListOf<PointF>()
        for(pt in pts){
            if(simplified.size<2||!collinear(simplified[simplified.lastIndex-1],simplified.last(),pt))simplified+=pt else simplified[simplified.lastIndex]=pt
        }
        return simplified
    }

    private fun heuristic(a:Pair<Int,Int>,b:Pair<Int,Int>)=abs(a.first-b.first)+abs(a.second-b.second).toFloat()
    private fun collinear(a:PointF,b:PointF,c:PointF):Boolean{val abx=b.x-a.x;val aby=b.y-a.y;val bcx=c.x-b.x;val bcy=c.y-b.y;return abs(abx*bcy-aby*bcx)<1f}

    private fun routeConnection(a:FlowElement,b:FlowElement,fromSide:ConnectionSide,toSide:ConnectionSide,gesture:List<PointF>):List<PointF>{
        val start=explicitEndpoint(a,fromSide);val end=explicitEndpoint(b,toSide)
        val obstacles=obstacleRects(a.id,b.id)+listOf(
            RectF(a.x-24f,a.y-24f,a.x+a.width+24f,a.y+a.height+24f),
            RectF(b.x-24f,b.y-24f,b.x+b.width+24f,b.y+b.height+24f)
        )
        val outwardFrom=when(fromSide){ConnectionSide.TOP->PointF(start.x,start.y-28f);ConnectionSide.RIGHT->PointF(start.x+28f,start.y);ConnectionSide.BOTTOM->PointF(start.x,start.y+28f);ConnectionSide.LEFT->PointF(start.x-28f,start.y);else->start}
        val outwardTo=when(toSide){ConnectionSide.TOP->PointF(end.x,end.y-28f);ConnectionSide.RIGHT->PointF(end.x+28f,end.y);ConnectionSide.BOTTOM->PointF(end.x,end.y+28f);ConnectionSide.LEFT->PointF(end.x-28f,end.y);else->end}
        val hints=simplifyGesture(gesture).filter{p->!obstacles.any{it.contains(p.x,p.y)}}.drop(1).dropLast(1).take(6)
        val anchors=mutableListOf<PointF>();anchors+=start;anchors+=outwardFrom;anchors+=hints;anchors+=outwardTo;anchors+=end
        val result=mutableListOf<PointF>()
        for(i in 0 until anchors.lastIndex){val seg=routeSegment(anchors[i],anchors[i+1],obstacles,hints);if(i==0)result.addAll(seg) else result.addAll(seg.drop(1))}
        if(result.size<2)result.add(end)
        return result
    }

    private fun drawConnectionPreview(c:Canvas){
        val start=document.elements.firstOrNull{it.id==connectionStartId} ?: return
        if(connectionGesture.size<2)return
        val points=connectionGesture
        paint.style=Paint.Style.STROKE;paint.strokeWidth=5f;paint.color=0xff2563eb.toInt();paint.pathEffect=null
        val p=Path();p.moveTo(points.first().x,points.first().y)
        for(i in 1 until points.size){val prev=points[i-1];val cur=points[i];p.quadTo((prev.x+cur.x)/2f,(prev.y+cur.y)/2f,cur.x,cur.y)}
        c.drawPath(p,paint)
        val w=connectionPreview;paint.style=Paint.Style.FILL;paint.color=0xff2563eb.toInt();c.drawCircle(w.x,w.y,5f,paint)
        paint.style=Paint.Style.STROKE
        // Highlight the four face centers on the source block so the chosen side is obvious.
        val r=RectF(start.x,start.y,start.x+start.width,start.y+start.height);val side=endpointSide(start,connectionStartPoint);val q=explicitEndpoint(start,side);c.drawCircle(q.x,q.y,7f,paint)
    }

    fun beginConnectionMode(){ connectionMode=true; connectionStartId=null; connectionGesture.clear(); connectionPreview.set(0f,0f); invalidate(); onSelectionChanged?.invoke() }
    fun beginConnectionFrom(id:String){ connectionMode=true; connectionStartId=id; connectionGesture.clear(); selectedElementId=id; selectedConnectionId=null; invalidate(); onSelectionChanged?.invoke() }
    fun cancelConnectionMode(){ connectionMode=false; connectionStartId=null; connectionGesture.clear(); invalidate(); onConnectionCancelled?.invoke(); onSelectionChanged?.invoke() }

    private fun drawArrow(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,type:ArrowType){
        val ang=atan2(y2-y1,x2-x1); val len=20f
        fun head(x:Float,y:Float,a:Float,diamond:Boolean=false){ val p=Path(); if(diamond){p.moveTo(x,y);p.lineTo(x-len*.8f*cos(a-.5f),y-len*.8f*sin(a-.5f));p.lineTo(x-len*cos(a),y-len*sin(a));p.lineTo(x-len*.8f*cos(a+.5f),y-len*.8f*sin(a+.5f));p.close()}else{p.moveTo(x,y);p.lineTo(x-len*cos(a-.5f),y-len*sin(a-.5f));p.lineTo(x-len*cos(a+.5f),y-len*sin(a+.5f));p.close()};paint.style=Paint.Style.FILL;c.drawPath(p,paint)}
        when(type){ArrowType.END->head(x2,y2,ang);ArrowType.BOTH->{head(x2,y2,ang);head(x1,y1,ang+PI.toFloat())};ArrowType.CIRCLE->{paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;c.drawCircle(x2,y2,7f,paint)};ArrowType.DIAMOND->head(x2,y2,ang,true);else->Unit}
    }

    private fun drawRepeatedArrows(c:Canvas,path:Path){
        val measure=PathMeasure(path,false);val length=measure.length;if(length<=1f)return
        val pos=FloatArray(2);val tan=FloatArray(2);var d=55f
        while(d<length-12f){if(measure.getPosTan(d,pos,tan))drawArrow(c,pos[0]-tan[0]*8f,pos[1]-tan[1]*8f,pos[0],pos[1],ArrowType.END);d+=70f}
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                gestureMoved=false;lastX=event.x;lastY=event.y;val w=world(event.x,event.y)
                if(connectionMode){val hit=hitElement(w.x,w.y);if(connectionStartId==null&&hit!=null){connectionStartId=hit.id;selectedElementId=hit.id;selectedConnectionId=null;connectionStartPoint.set(w.x,w.y);connectionGesture.clear();connectionGesture.add(PointF(w.x,w.y));connectionPreview.set(w.x,w.y);invalidate();onSelectionChanged?.invoke();return true};if(connectionStartId!=null){connectionGesture.add(PointF(w.x,w.y));connectionPreview.set(w.x,w.y);invalidate();return true}}
                val selected=selectedElement()
                if(selected!=null){val h=handleAt(selected,w.x,w.y);if(h!=Handle.NONE){resizeId=selected.id;resizeHandle=h;dragId=null;startResize=RectF(selected.x,selected.y,selected.x+selected.width,selected.y+selected.height);return true};if(!lastNotesButton.isEmpty&&lastNotesButton.contains(w.x,w.y)){onNotesTap?.invoke(selected);return true}}
                val hit=hitElement(w.x,w.y)
                if(hit!=null){selectedElementId=hit.id;selectedConnectionId=null;dragId=hit.id;dragOffsetX=w.x-hit.x;dragOffsetY=w.y-hit.y;startMoveX=hit.x;startMoveY=hit.y;val now=System.currentTimeMillis();if(now-lastTap<300)onDoubleTapElement?.invoke(hit);lastTap=now}
                else{selectedElementId=null;selectedConnectionId=hitConnection(w.x,w.y)?.id}
                onSelectionChanged?.invoke();invalidate();return true
            }
            MotionEvent.ACTION_MOVE->{
                if(event.pointerCount>1){gestureMoved=true;return true};val w=world(event.x,event.y)
                if(connectionMode&&connectionStartId!=null){connectionGesture.add(PointF(w.x,w.y));connectionPreview.set(w.x,w.y);gestureMoved=true;invalidate();return true}
                if(resizeId!=null){resize(selectedElement()?:return true,w.x,w.y);gestureMoved=true}
                else if(dragId!=null){selectedElement()?.let{it.x=w.x-dragOffsetX;it.y=w.y-dragOffsetY;if(snapToGrid){it.x=round(it.x/gridSize)*gridSize;it.y=round(it.y/gridSize)*gridSize}};gestureMoved=true}
                else{panX+=event.x-lastX;panY+=event.y-lastY;gestureMoved=true}
                lastX=event.x;lastY=event.y;invalidate();return true
            }
            MotionEvent.ACTION_UP->{
                if(connectionMode&&connectionStartId!=null){
                    val w=world(event.x,event.y); val target=hitElement(w.x,w.y); val source=connectionStartId ?: return true
                    if(target!=null&&target.id!=source){
                        val start=document.elements.firstOrNull{it.id==source}; if(start!=null){
                            val fromSide=endpointSide(start,connectionStartPoint); val toSide=endpointSide(target,w)
                            val route=routeConnection(start,target,fromSide,toSide,connectionGesture)
                            onConnectionRequested?.invoke(source,target.id,fromSide,toSide,route)
                        }
                        return true
                    }
                    if(!gestureMoved&&target==null){connectionStartId=null;connectionGesture.clear();invalidate();onSelectionChanged?.invoke()};return true
                }
                val e=selectedElement();if(dragId!=null&&e!=null&&(e.x!=startMoveX||e.y!=startMoveY))onMoveFinished?.invoke(e,startMoveX,startMoveY)
                if(resizeId!=null&&e!=null){val old=startResize;if(old.left!=e.x||old.top!=e.y||old.width()!=e.width||old.height()!=e.height)onResizeFinished?.invoke(e,old.left,old.top,old.width(),old.height())}
                dragId=null;resizeId=null;resizeHandle=Handle.NONE;return true
            }
            MotionEvent.ACTION_CANCEL->{dragId=null;resizeId=null;resizeHandle=Handle.NONE;if(connectionMode)cancelConnectionMode();return true}
        };return true
    }

    private fun resize(e:FlowElement,x:Float,y:Float){
        var l=e.x;var t=e.y;var r=e.x+e.width;var b=e.y+e.height;val minW=70f;val minH=45f
        when(resizeHandle){Handle.TL->{l=min(x,r-minW);t=min(y,b-minH)};Handle.T->{t=min(y,b-minH)};Handle.TR->{r=max(x,l+minW);t=min(y,b-minH)};Handle.L->{l=min(x,r-minW)};Handle.R->{r=max(x,l+minW)};Handle.BL->{l=min(x,r-minW);b=max(y,t+minH)};Handle.B->{b=max(y,t+minH)};Handle.BR->{r=max(x,l+minW);b=max(y,t+minH)};else->Unit}
        if(snapToGrid){l=round(l/gridSize)*gridSize;t=round(t/gridSize)*gridSize;r=round(r/gridSize)*gridSize;b=round(b/gridSize)*gridSize}
        e.x=l;e.y=t;e.width=r-l;e.height=b-t
    }
    private fun handlePoints(r:RectF)=listOf(PointF(r.left,r.top),PointF(r.centerX(),r.top),PointF(r.right,r.top),PointF(r.left,r.centerY()),PointF(r.right,r.centerY()),PointF(r.left,r.bottom),PointF(r.centerX(),r.bottom),PointF(r.right,r.bottom))
    private fun handleAt(e:FlowElement,x:Float,y:Float):Handle{
        val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height)
        val margin=maxOf(34f/scale,22f)
        fun near(px:Float,py:Float):Boolean = hypot(x-px,y-py)<=margin
        if(near(r.left,r.top)) return Handle.TL
        if(near(r.right,r.top)) return Handle.TR
        if(near(r.left,r.bottom)) return Handle.BL
        if(near(r.right,r.bottom)) return Handle.BR
        if(abs(y-r.top)<=margin&&x>=r.left-margin&&x<=r.right+margin) return Handle.T
        if(abs(y-r.bottom)<=margin&&x>=r.left-margin&&x<=r.right+margin) return Handle.B
        if(abs(x-r.left)<=margin&&y>=r.top-margin&&y<=r.bottom+margin) return Handle.L
        if(abs(x-r.right)<=margin&&y>=r.top-margin&&y<=r.bottom+margin) return Handle.R
        return Handle.NONE
    }
    private fun world(x:Float,y:Float)=PointF((x-panX)/scale,(y-panY)/scale)
    private fun selectedElement()=document.elements.firstOrNull{it.id==selectedElementId}
    private fun hitElement(x:Float,y:Float)=document.elements.asReversed().firstOrNull{hitShape(it,x,y)}
    private fun hitShape(e:FlowElement,x:Float,y:Float):Boolean{val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height);return when(e.shape){ShapeType.DIAMOND->abs(x-r.centerX())/r.width()+abs(y-r.centerY())/r.height()<=.5f;else->r.contains(x,y)}}
    private fun hitConnection(x:Float,y:Float):FlowConnection?{
        val threshold=maxOf(22f,24f/scale)
        return document.connections.asReversed().firstOrNull{con->
            val a=document.elements.firstOrNull{it.id==con.fromId}?:return@firstOrNull false
            val b=document.elements.firstOrNull{it.id==con.toId}?:return@firstOrNull false
            val pair=document.connections.filter{(it.fromId==con.fromId&&it.toId==con.toId)||(it.fromId==con.toId&&it.toId==con.fromId)}.sortedBy{it.id}
            val index=pair.indexOfFirst{it.id==con.id}.coerceAtLeast(0);val autoEndpoints=connectionEndpoints(a,b,index,pair.size);val p1=if(con.fromSide==ConnectionSide.AUTO)autoEndpoints.first else explicitEndpoint(a,con.fromSide);val p2=if(con.toSide==ConnectionSide.AUTO)autoEndpoints.second else explicitEndpoint(b,con.toSide);val path=if(con.routePoints.size>=2){val route=con.routePoints.map{PointF(it.x,it.y)}.toMutableList();route[0]=p1;route[route.lastIndex]=p2;buildRoutedPath(route)} else buildConnectionPath(p1,p2,con.bendX,con.bendY,index);val pm=PathMeasure(path,false);val pos=FloatArray(2);var d=0f;var hit=false
            while(d<=pm.length){if(pm.getPosTan(d,pos,null)&&hypot(x-pos[0],y-pos[1])<=threshold){hit=true;break};d+=maxOf(6f,threshold/2f)};hit
        }
    }
    private fun wrap(s:String,max:Int):List<String>{if(s.isBlank())return listOf("");val out=mutableListOf<String>();var rest=s;while(rest.length>max){val cut=rest.substring(0,max).lastIndexOf(' ').let{if(it>0)it else max};out+=rest.substring(0,cut);rest=rest.substring(cut).trimStart()};out+=rest;return out}
    fun resetViewport(){scale=1f;panX=0f;panY=0f;invalidate()}
    fun fitContent(){if(document.elements.isEmpty()){resetViewport();return};val minX=document.elements.minOf{it.x};val minY=document.elements.minOf{it.y};val maxX=document.elements.maxOf{it.x+it.width};val maxY=document.elements.maxOf{it.y+it.height};val pad=80f;val sx=width/(maxX-minX+pad*2);val sy=height/(maxY-minY+pad*2);scale=min(sx,sy).coerceIn(.25f,5f);panX=width/2f-(minX+(maxX-minX)/2f)*scale;panY=height/2f-(minY+(maxY-minY)/2f)*scale;invalidate()}
}
