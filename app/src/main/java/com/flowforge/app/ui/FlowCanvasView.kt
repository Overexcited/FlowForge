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
    var onElementAction: ((FlowElement) -> Unit)? = null
    var onConnectionRequested: ((String, String) -> Unit)? = null
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f; private var panX = 0f; private var panY = 0f
    private var lastX = 0f; private var lastY = 0f; private var lastTap = 0L
    private var dragId: String? = null; private var dragOffsetX = 0f; private var dragOffsetY = 0f
    private var startMoveX = 0f; private var startMoveY = 0f
    private var resizeId: String? = null; private var resizeHandle = Handle.NONE
    private var startResize = RectF(); private var lastActionButton = RectF(); private var lastNotesButton = RectF()
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

    private fun drawElement(c: Canvas, e: FlowElement) {
        val r = RectF(e.x, e.y, e.x + e.width, e.y + e.height)
        paint.style = Paint.Style.FILL
        paint.color = if (darkMode) when (e.type) {
            ElementType.SERVER -> 0xff1e3a5f.toInt(); ElementType.DECISION -> 0xff594a16.toInt()
            ElementType.TERMINAL -> 0xff164e3b.toInt(); ElementType.DATA -> 0xff4a2857.toInt(); else -> 0xff1e293b.toInt()
        } else when (e.type) {
            ElementType.SERVER -> 0xffe8f1ff.toInt(); ElementType.DECISION -> 0xfffff3cd.toInt()
            ElementType.TERMINAL -> 0xffe8f5e9.toInt(); ElementType.DATA -> 0xfff3e5f5.toInt(); else -> 0xfff7f7f8.toInt()
        }
        drawShape(c, e, r, fill = true)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = if (e.id == selectedElementId) 5f else 2.5f
        paint.color = if (e.id == selectedElementId) 0xff2563eb.toInt() else if (darkMode) 0xff94a3b8.toInt() else 0xff334155.toInt()
        drawShape(c, e, r, fill = false)
        textPaint.color = if (darkMode) Color.WHITE else 0xff172033.toInt(); textPaint.textSize = 25f
        val maxChars = max(8, (e.width / 15f).toInt()); val lines = wrap(e.label, maxChars).take(4)
        val lineH = 29f; val base = e.y + e.height / 2f - (lines.size - 1) * lineH / 2f + 9f
        lines.forEachIndexed { i, s -> c.drawText(s, e.x + e.width/2f - textPaint.measureText(s)/2f, base + i*lineH, textPaint) }
        if (e.notes.isNotBlank()) drawBadge(c, e.x + e.width - 14f, e.y + 14f, true)
    }

    private fun drawShape(c: Canvas, e: FlowElement, r: RectF, fill: Boolean) {
        if (!fill && e.shape == ShapeType.DOCUMENT) { /* normal outline below */ }
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
        val button=RectF(r.right+10f,r.top-46f,r.right+54f,r.top-2f); lastActionButton=button
        paint.style=Paint.Style.FILL; paint.color=0xff2563eb.toInt(); c.drawRoundRect(button,12f,12f,paint)
        textPaint.color=Color.WHITE; textPaint.textSize=25f; c.drawText("⋮",button.centerX()-5f,button.centerY()+9f,textPaint)
        if(e.notes.isNotBlank()){ lastNotesButton=RectF(r.right-30f,r.top-30f,r.right+2f,r.top+2f) } else lastNotesButton.setEmpty()
    }

    private fun drawBadge(c:Canvas,x:Float,y:Float,info:Boolean){ paint.style=Paint.Style.FILL;paint.color=0xfff59e0b.toInt();c.drawCircle(x,y,10f,paint);textPaint.color=Color.WHITE;textPaint.textSize=13f;c.drawText(if(info)"i" else "!",x-2.3f,y+4.5f,textPaint) }

    private fun drawConnection(c: Canvas, con: FlowConnection) {
        val a=document.elements.firstOrNull{it.id==con.fromId} ?: return
        val b=document.elements.firstOrNull{it.id==con.toId} ?: return
        val pair=document.connections.filter{it.fromId==con.fromId && it.toId==con.toId}
        val pairIndex=pair.indexOfFirst{it.id==con.id}.coerceAtLeast(0)
        val (p1,p2)=connectionEndpoints(a,b,pairIndex,pair.size)
        val path=buildConnectionPath(p1,p2,con.bendX,con.bendY,pairIndex)
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=if(con.id==selectedConnectionId)7f else 3.5f
        paint.color=if(con.id==selectedConnectionId)0xff2563eb.toInt() else if(darkMode)0xffcbd5e1.toInt() else 0xff475569.toInt()
        paint.pathEffect=when(con.lineStyle){LineStyle.DASHED->DashPathEffect(floatArrayOf(18f,12f),0f);LineStyle.DOTTED->DashPathEffect(floatArrayOf(4f,10f),0f);else->null}
        c.drawPath(path,paint);paint.pathEffect=null
        val tangent=pathTangent(p1,p2,con.bendX,con.bendY,pairIndex)
        if(con.arrowType!=ArrowType.NONE) drawArrow(c,tangent.first.x,tangent.first.y,tangent.second.x,tangent.second.y,con.arrowType)
        val mid=connectionMidpoint(p1,p2,con.bendX,con.bendY,pairIndex)
        if(con.label.isNotBlank()){textPaint.color=if(darkMode)Color.WHITE else 0xff334155.toInt();textPaint.textSize=21f;c.drawText(con.label,mid.x+6,mid.y-6,textPaint)}
        if(con.notes.isNotBlank()) drawBadge(c,mid.x+12,mid.y-18,false)
    }

    private fun connectionEndpoints(a:FlowElement,b:FlowElement,index:Int,count:Int):Pair<PointF,PointF>{
        val acx=a.x+a.width/2f; val acy=a.y+a.height/2f; val bcx=b.x+b.width/2f; val bcy=b.y+b.height/2f
        val dx=bcx-acx; val dy=bcy-acy
        val side = if(abs(dy)>=abs(dx)){if(dy>=0)1 else 3}else{if(dx>=0)2 else 4}
        val spread=if(count<=1)0f else ((index-(count-1)/2f)*28f)
        fun point(e:FlowElement,side:Int,offset:Float):PointF=when(side){1->PointF(e.x+e.width/2f+offset,e.y+e.height);3->PointF(e.x+e.width/2f+offset,e.y);2->PointF(e.x+e.width,e.y+e.height/2f+offset);else->PointF(e.x,e.y+e.height/2f+offset)}
        val opposite=when(side){1->3;3->1;2->4;else->2}
        return point(a,side,spread) to point(b,opposite,spread)
    }

    private fun buildConnectionPath(p1:PointF,p2:PointF,bx:Float,by:Float,index:Int):Path{
        val p=Path()
        p.moveTo(p1.x,p1.y)
        val dx=p2.x-p1.x; val dy=p2.y-p1.y
        if(bx!=0f||by!=0f){
            p.quadTo((p1.x+p2.x)/2f+bx,(p1.y+p2.y)/2f+by,p2.x,p2.y)
        }else if(abs(dy)>=abs(dx)){
            val mid=(p1.y+p2.y)/2f + if(index%2==0) 0f else 18f
            p.cubicTo(p1.x,mid,p2.x,mid,p2.x,p2.y)
        }else{
            val mid=(p1.x+p2.x)/2f + if(index%2==0) 0f else 18f
            p.cubicTo(mid,p1.y,mid,p2.y,p2.x,p2.y)
        }
        return p
    }

    private fun connectionMidpoint(p1:PointF,p2:PointF,bx:Float,by:Float,index:Int):PointF{
        return PointF((p1.x+p2.x)/2f+bx/2f,(p1.y+p2.y)/2f+by/2f)
    }

    private fun pathTangent(p1:PointF,p2:PointF,bx:Float,by:Float,index:Int):Pair<PointF,PointF>{
        val dx=p2.x-p1.x; val dy=p2.y-p1.y
        return if(abs(dy)>=abs(dx)){
            val mid=(p1.y+p2.y)/2f + if(index%2==0)0f else 18f
            PointF(p2.x,mid) to p2
        }else{
            val mid=(p1.x+p2.x)/2f + if(index%2==0)0f else 18f
            PointF(mid,p2.y) to p2
        }
    }

    private fun drawConnectionPreview(c:Canvas){
        val start=document.elements.firstOrNull{it.id==connectionStartId} ?: return
        val end=connectionPreview
        paint.style=Paint.Style.STROKE;paint.strokeWidth=5f;paint.color=0xff2563eb.toInt();paint.pathEffect=DashPathEffect(floatArrayOf(14f,10f),0f)
        val p=Path();p.moveTo(start.x+start.width/2f,start.y+start.height/2f);p.quadTo((start.x+start.width/2f+end.x)/2f,(start.y+start.height/2f+end.y)/2f,end.x,end.y)
        c.drawPath(p,paint);paint.pathEffect=null
    }

    fun beginConnectionMode(){ connectionMode=true; connectionStartId=null; connectionPreview.set(0f,0f); invalidate(); onSelectionChanged?.invoke() }
    fun beginConnectionFrom(id:String){ connectionMode=true; connectionStartId=id; selectedElementId=id; selectedConnectionId=null; invalidate(); onSelectionChanged?.invoke() }
    fun cancelConnectionMode(){ connectionMode=false; connectionStartId=null; invalidate(); onConnectionCancelled?.invoke(); onSelectionChanged?.invoke() }

    private fun drawArrow(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,type:ArrowType){
        val ang=atan2(y2-y1,x2-x1); val len=20f
        fun head(x:Float,y:Float,a:Float,diamond:Boolean=false){ val p=Path(); if(diamond){p.moveTo(x,y);p.lineTo(x-len*.8f*cos(a-.5f),y-len*.8f*sin(a-.5f));p.lineTo(x-len*cos(a),y-len*sin(a));p.lineTo(x-len*.8f*cos(a+.5f),y-len*.8f*sin(a+.5f));p.close()}else{p.moveTo(x,y);p.lineTo(x-len*cos(a-.5f),y-len*sin(a-.5f));p.lineTo(x-len*cos(a+.5f),y-len*sin(a+.5f));p.close()};paint.style=Paint.Style.FILL;c.drawPath(p,paint)}
        when(type){ArrowType.END->head(x2,y2,ang);ArrowType.BOTH->{head(x2,y2,ang);head(x1,y1,ang+PI.toFloat())};ArrowType.CIRCLE->{paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;c.drawCircle(x2,y2,7f,paint)};ArrowType.DIAMOND->head(x2,y2,ang,true);else->Unit}
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                gestureMoved=false;lastX=event.x;lastY=event.y
                val w=world(event.x,event.y)
                if(connectionMode){
                    val hit=hitElement(w.x,w.y)
                    if(connectionStartId==null && hit!=null){connectionStartId=hit.id;selectedElementId=hit.id;selectedConnectionId=null;connectionPreview.set(w.x,w.y);invalidate();onSelectionChanged?.invoke();return true}
                    if(connectionStartId!=null){connectionPreview.set(w.x,w.y);invalidate();return true}
                }
                val selected=selectedElement()
                if(selected!=null){
                    if(lastActionButton.contains(w.x,w.y)){onElementAction?.invoke(selected);return true}
                    if(!lastNotesButton.isEmpty && lastNotesButton.contains(w.x,w.y)){onNotesTap?.invoke(selected);return true}
                    val h=handleAt(selected,w.x,w.y)
                    if(h!=Handle.NONE){resizeId=selected.id;resizeHandle=h;startResize=RectF(selected.x,selected.y,selected.x+selected.width,selected.y+selected.height);return true}
                }
                val hit=hitElement(w.x,w.y)
                if(hit!=null){selectedElementId=hit.id;selectedConnectionId=null;dragId=hit.id;dragOffsetX=w.x-hit.x;dragOffsetY=w.y-hit.y;startMoveX=hit.x;startMoveY=hit.y
                    val now=System.currentTimeMillis();if(now-lastTap<300)onDoubleTapElement?.invoke(hit);lastTap=now
                }else{selectedElementId=null;selectedConnectionId=hitConnection(w.x,w.y)?.id}
                onSelectionChanged?.invoke();invalidate();return true
            }
            MotionEvent.ACTION_MOVE->{
                if(event.pointerCount>1){gestureMoved=true;return true}
                val w=world(event.x,event.y)
                if(connectionMode && connectionStartId!=null){connectionPreview.set(w.x,w.y);gestureMoved=true;invalidate();return true}
                if(resizeId!=null){resize(selectedElement() ?: return true,w.x,w.y);gestureMoved=true}
                else if(dragId!=null){selectedElement()?.let{it.x=w.x-dragOffsetX;it.y=w.y-dragOffsetY;if(snapToGrid){it.x=round(it.x/gridSize)*gridSize;it.y=round(it.y/gridSize)*gridSize}};gestureMoved=true}
                else {panX+=event.x-lastX;panY+=event.y-lastY;gestureMoved=true}
                lastX=event.x;lastY=event.y;invalidate();return true
            }
            MotionEvent.ACTION_UP->{
                if(connectionMode && connectionStartId!=null){
                    val w=world(event.x,event.y);val target=hitElement(w.x,w.y);val source=connectionStartId
                    if(target!=null && target.id!=source){onConnectionRequested?.invoke(source!!,target.id);return true}
                    if(!gestureMoved && target==null){connectionStartId=null;invalidate();onSelectionChanged?.invoke()}
                    return true
                }
                val e=selectedElement()
                if(dragId!=null && e!=null && (e.x!=startMoveX||e.y!=startMoveY)) onMoveFinished?.invoke(e,startMoveX,startMoveY)
                if(resizeId!=null && e!=null){val old=startResize;if(old.left!=e.x||old.top!=e.y||old.width()!=e.width||old.height()!=e.height)onResizeFinished?.invoke(e,old.left,old.top,old.width(),old.height())}
                dragId=null;resizeId=null;resizeHandle=Handle.NONE;return true
            }
            MotionEvent.ACTION_CANCEL->{dragId=null;resizeId=null;resizeHandle=Handle.NONE;if(connectionMode)cancelConnectionMode();return true}
        };return true
    }

    private fun resize(e:FlowElement,x:Float,y:Float){
        var l=e.x;var t=e.y;var r=e.x+e.width;var b=e.y+e.height; val minW=70f;val minH=45f
        when(resizeHandle){Handle.TL->{l=min(x,r-minW);t=min(y,b-minH)};Handle.T->{t=min(y,b-minH)};Handle.TR->{r=max(x,l+minW);t=min(y,b-minH)};Handle.L->{l=min(x,r-minW)};Handle.R->{r=max(x,l+minW)};Handle.BL->{l=min(x,r-minW);b=max(y,t+minH)};Handle.B->{b=max(y,t+minH)};Handle.BR->{r=max(x,l+minW);b=max(y,t+minH)};else->Unit}
        if(snapToGrid){l=round(l/gridSize)*gridSize;t=round(t/gridSize)*gridSize;r=round(r/gridSize)*gridSize;b=round(b/gridSize)*gridSize}
        e.x=l;e.y=t;e.width=r-l;e.height=b-t
    }

    private fun handlePoints(r:RectF)=listOf(PointF(r.left,r.top),PointF(r.centerX(),r.top),PointF(r.right,r.top),PointF(r.left,r.centerY()),PointF(r.right,r.centerY()),PointF(r.left,r.bottom),PointF(r.centerX(),r.bottom),PointF(r.right,r.bottom))
    private fun handleAt(e:FlowElement,x:Float,y:Float):Handle{val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height);val pts=handlePoints(r);val names=Handle.values().drop(1);val hit=pts.indexOfFirst{hypot(x-it.x,y-it.y)<=18f};return if(hit>=0)names[hit] else Handle.NONE}
    private fun world(x:Float,y:Float)=PointF((x-panX)/scale,(y-panY)/scale)
    private fun selectedElement()=document.elements.firstOrNull{it.id==selectedElementId}
    private fun hitElement(x:Float,y:Float)=document.elements.asReversed().firstOrNull{hitShape(it,x,y)}
    private fun hitShape(e:FlowElement,x:Float,y:Float):Boolean{val r=RectF(e.x,e.y,e.x+e.width,e.y+e.height);return when(e.shape){ShapeType.DIAMOND->abs(x-r.centerX())/r.width()+abs(y-r.centerY())/r.height()<=.5f;else->r.contains(x,y)}}
    private fun hitConnection(x:Float,y:Float):FlowConnection?=document.connections.asReversed().firstOrNull{con->val a=document.elements.firstOrNull{it.id==con.fromId}?:return@firstOrNull false;val b=document.elements.firstOrNull{it.id==con.toId}?:return@firstOrNull false;val mx=(a.x+b.x+a.width+b.width)/4+con.bendX/2;val my=(a.y+b.y+a.height+b.height)/4+con.bendY/2;hypot(x-mx,y-my)<30f}
    private fun wrap(s:String,max:Int):List<String>{if(s.isBlank())return listOf("");val out=mutableListOf<String>();var rest=s;while(rest.length>max){val cut=rest.substring(0,max).lastIndexOf(' ').let{if(it>0)it else max};out+=rest.substring(0,cut);rest=rest.substring(cut).trimStart()};out+=rest;return out}
    fun resetViewport(){scale=1f;panX=0f;panY=0f;invalidate()}
    fun fitContent(){if(document.elements.isEmpty()){resetViewport();return};val minX=document.elements.minOf{it.x};val minY=document.elements.minOf{it.y};val maxX=document.elements.maxOf{it.x+it.width};val maxY=document.elements.maxOf{it.y+it.height};val pad=80f;val sx=width/(maxX-minX+pad*2);val sy=height/(maxY-minY+pad*2);scale=min(sx,sy).coerceIn(.25f,5f);panX=width/2f-(minX+(maxX-minX)/2f)*scale;panY=height/2f-(minY+(maxY-minY)/2f)*scale;invalidate()}
}
