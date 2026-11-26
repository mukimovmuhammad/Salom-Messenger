package com.mukimov.salom

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessage(message: Message, maxWidth: androidx.compose.ui.unit.Dp, media: MediaFiles, player: ChatAudioPlayer, grouped: Boolean, onReply: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null, onLocation: ((String) -> Unit)? = null, peerName: String = "Собеседник", onQuote: ((Long) -> Unit)? = null, onReact: (() -> Unit)? = null, onPreview: ((Attachment) -> Unit)? = null) {
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val textColor = if (message.mine) (if (dark) Color(0xFFFFF0BD) else Ink) else (if (dark) Color(0xFFECF1F6) else Ink)
    val bubbleColor = if (message.mine) (if (dark) Color(0xFF45452C) else Color(0xFFFFEDB2)) else (if (dark) Color(0xFF23313D) else Color.White)
    val reactions = listOfNotNull(message.myReaction, message.peerReaction).groupingBy { it }.eachCount()
    val photo = message.attachment?.previewKind() == PreviewKind.PHOTO
    val actions = Modifier.combinedClickable(
        enabled = onReact != null || onLongPress != null || onReply != null,
        onClickLabel = if (onReact != null) "Поставить реакцию" else null,
        onClick = { onReact?.invoke() ?: onLongPress?.invoke() ?: onReply?.invoke() },
        onLongClickLabel = "Действия с сообщением",
        onLongClick = onLongPress ?: onReply,
    )
    val emoji = reactions.isEmpty() && message.attachment == null && message.replyPreview == null && !message.edited && VoicePolicy.emojiOnly(message.text)
    Row(Modifier.fillMaxWidth().padding(top = if (grouped) 1.dp else 5.dp), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
        if (emoji) Column(actions, horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
            Text(message.text, fontSize = 44.sp, lineHeight = 55.sp, modifier = Modifier.padding(horizontal = 6.dp))
            if (message.time.isNotEmpty()) MessageMeta(message.time, message.receipt, message.mine, dark, Modifier.clip(RoundedCornerShape(8.dp)).background(if (dark) Color(0x30202D38) else Color(0x20FFFFFF)).padding(horizontal = 5.dp, vertical = 1.dp))
        } else Column(Modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(17.dp,17.dp,if (message.mine) 5.dp else 17.dp,if (message.mine) 17.dp else 5.dp)).background(bubbleColor).then(actions).padding(horizontal=if (photo) 0.dp else 10.dp,vertical=if (photo) 0.dp else 6.dp)) {
            message.replyPreview?.let { preview ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(if (dark) Color.White.copy(alpha = .055f) else Color.Black.copy(alpha = .045f))
                    .clickable(enabled = message.replyToId != null && onQuote != null, onClickLabel = "Перейти к сообщению") {
                        message.replyToId?.let { onQuote?.invoke(it) }
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(2.dp).heightIn(min = 27.dp).background(if (message.mine) Yellow else Color(0xFF4AA5E5)))
                    Column(Modifier.weight(1f).padding(start = 7.dp, end = 4.dp)) {
                        Text(message.replyAuthor?.takeUnless { it == "Собеседник" } ?: peerName, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = if (message.mine) (if (dark) Yellow else Color(0xFF987400)) else Color(0xFF4AA5E5), maxLines = 1)
                        Text(preview, fontSize = 11.sp, lineHeight = 14.sp, color = textColor.copy(alpha = .88f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            val attachment = message.attachment
            message.locationId?.let { id ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(dark) Color(0xFF314134) else Color(0xFFEAF4E5)).clickable { onLocation?.invoke(id) }.padding(horizontal=10.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("⌖",fontSize=23.sp,color=Color(0xFF258A50)); Column(Modifier.padding(start=8.dp)) {
                        Text("Местоположение",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=textColor)
                        Text(if(message.mine) "Открыть карту или остановить передачу" else "Открыть в Google Картах",fontSize=10.sp,color=textColor.copy(alpha=.7f))
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
            if (attachment != null) {
                if (attachment.mime.startsWith("audio/")) AudioBubble(attachment, player, textColor)
                else AttachmentBubble(attachment, media, textColor, maxWidth, onLongPress, onPreview,
                    photoMeta = if (photo && message.time.isNotEmpty()) {
                        {
                            MessageMeta(message.time, message.receipt, message.mine, true,
                                Modifier.padding(5.dp).clip(RoundedCornerShape(7.dp))
                                    .background(Color.Black.copy(alpha = .68f)).padding(horizontal = 5.dp, vertical = 3.dp),
                                Color.White)
                        }
                    } else null)
            }
            if (message.text.isNotEmpty() && attachment == null) {
                BubbleText(message.text, message.time, message.receipt, message.mine, dark, textColor, maxWidth - 20.dp)
            } else {
                if (message.text.isNotEmpty()) Text(linkedMessage(message.text), color=textColor, fontSize=15.sp,lineHeight=20.sp,
                    modifier=if (photo) Modifier.padding(horizontal = 10.dp, vertical = 6.dp) else Modifier.padding(top=3.dp))
                if (!photo && message.time.isNotEmpty()) MessageMeta(message.time, message.receipt, message.mine, dark, Modifier.align(Alignment.End).padding(top=2.dp), textColor)
            }
            if (message.edited) Text("изменено",fontSize=9.sp,fontStyle=androidx.compose.ui.text.font.FontStyle.Italic,color=textColor.copy(alpha=.6f),modifier=Modifier.align(Alignment.End).padding(top=1.dp))
            if (reactions.isNotEmpty() || (onReact != null && (attachment != null || message.locationId != null))) {
                val label = reactions.entries.joinToString("   ") { (emoji, count) -> "$emoji $count" }
                val reactionClick = if (onReact != null) Modifier.clickable(onClickLabel = "Поставить реакцию", onClick = onReact) else Modifier
                Text(label.ifEmpty { "☺ +" }, color = textColor, fontSize = 12.sp,
                    modifier = Modifier.then(reactionClick).padding(horizontal = if (photo) 10.dp else 3.dp,
                        vertical = if (onReact != null) 9.dp else 4.dp))
            }
        }
    }
}

@Composable
private fun BubbleText(text: String, time: String, receipt: String, mine: Boolean, dark: Boolean, color: Color, maxWidth: androidx.compose.ui.unit.Dp) {
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val body = linkedMessage(text)
    val style = TextStyle(fontSize=15.sp,lineHeight=20.sp)
    val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
    val textMeasure = remember(text,width,density.fontScale) { measure.measure(AnnotatedString(text), style, maxLines=2, constraints=Constraints(maxWidth=width)) }
    val clockWidth = measure.measure(AnnotatedString(time), TextStyle(fontSize=9.sp)).size.width
    val inline = !text.contains('\n') && textMeasure.lineCount == 1 && textMeasure.size.width + clockWidth + with(density) { (if (mine) 25.dp else 9.dp).roundToPx() } <= width
    if (inline) Row(verticalAlignment=Alignment.Bottom) {
        Text(body,color=color,style=style)
        if (time.isNotEmpty()) MessageMeta(time, receipt, mine, dark, Modifier.padding(start=7.dp,bottom=1.dp), color)
    } else Column {
        Text(body,color=color,style=style)
        if (time.isNotEmpty()) MessageMeta(time, receipt, mine, dark, Modifier.align(Alignment.End).padding(top=1.dp), color)
    }
}

@Composable
private fun linkedMessage(text: String): AnnotatedString {
    val context = LocalContext.current
    return remember(text, context) {
        buildAnnotatedString {
            var end = 0
            webLinks.findAll(text).forEach { match ->
                val label = match.value.trimEnd('.', ',', '!', '?', ';', ':', '…').let {
                    var value = it
                    var extraClosings = value.count { it == ')' } - value.count { it == '(' }
                    while (extraClosings-- > 0 && value.endsWith(')')) value = value.dropLast(1)
                    value
                }
                val url = if (label.startsWith("www.", true)) "https://$label" else label
                val uri = runCatching { java.net.URI(url) }.getOrNull()
                if (uri?.host != null && uri.rawUserInfo == null) {
                    append(text.substring(end, match.range.first))
                    withLink(LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            if (ProfileLinks.username(url) != null) {
                                intent.setClass(context, MainActivity::class.java)
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            try { context.startActivity(intent) }
                            catch (_: android.content.ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, "Не удалось открыть ссылку", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )) { append(label) }
                    end = match.range.first + label.length
                }
            }
            append(text.substring(end))
        }
    }
}

private val webLinks = Regex("""(?<![\p{L}\p{N}_@])(?:https?://|www\.)[^\s<>\[\]{}"«»]+""", RegexOption.IGNORE_CASE)

@Composable
private fun MessageMeta(time: String, receipt: String, mine: Boolean, dark: Boolean, modifier: Modifier = Modifier, contentColor: Color = if (dark) Color(0xFFCAD6DD) else Color(0xFF71776B)) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(time, fontSize = 9.sp, lineHeight = 11.sp, color = contentColor.copy(alpha = .66f))
        if (mine) {
            Spacer(Modifier.width(3.dp))
            when (receipt) {
                "pending" -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.dp, color = contentColor.copy(alpha = .72f))
                "queued" -> Text("В очереди", fontSize = 9.sp, lineHeight = 11.sp, color = contentColor.copy(alpha = .72f))
                "failed" -> Text("Не отправлено", fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.error)
                "read" -> DoubleCheck(Color(0xFF38C878))
                "delivered" -> DoubleCheck(contentColor.copy(alpha = .78f))
                else -> Text("✓", fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium, color = contentColor.copy(alpha = .72f))
            }
        }
    }
}

@Composable
private fun DoubleCheck(color: Color) {
    Canvas(Modifier.size(width=14.dp,height=10.dp)) {
        val stroke=size.width*.11f
        fun check(x:Float) { val p=androidx.compose.ui.graphics.Path().apply { moveTo(x,size.height*.52f); lineTo(x+size.width*.16f,size.height*.78f); lineTo(x+size.width*.43f,size.height*.22f) }; drawPath(p,color,style=androidx.compose.ui.graphics.drawscope.Stroke(width=stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round)) }
        check(0f); check(size.width*.42f)
    }
}
