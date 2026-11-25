package com.jjlee.mynote.ink

enum class GestureTrigger(val label: String, val description: String) {
    PEN_DOUBLE_TAP("레노버 펜 두 번 탭 / 버튼 클릭", "펜 옆면(버튼) 두 번 탭 또는 호버 중 버튼 클릭"),
    BARREL_PRIMARY_HOLD("펜 배럴 주 버튼 홀드", "스타일러스 아래쪽 버튼을 누른 채 드래그"),
    BARREL_SECONDARY_PRESS("펜 배럴 보조 버튼 클릭", "스타일러스 위쪽 버튼 클릭"),
    SCRIBBLE("문지르기 (Scribble)", "펜으로 빠르게 좌우 왕복 긁기"),
    TWO_FINGER_TAP("두 손가락 탭", "화면을 두 손가락으로 가볍게 탭"),
    TWO_FINGER_SWIPE_UP("두 손가락 위로 올리기", "두 손가락을 나란히 아래에서 위로 쓸어올림 (ZUI 비중복)"),
    THREE_FINGER_TAP("세 손가락 탭", "화면을 세 손가락으로 가볍게 탭"),
    THREE_FINGER_SWIPE_UP("세 손가락 위로 올리기", "세 손가락으로 화면을 아래에서 위로 쓸어올림")
}

enum class GestureAction(val label: String) {
    SWAP_PEN_ERASER("지우개 ↔ 펜 스왑"),
    PREVIOUS_TOOL("이전 도구로 전환"),
    CYCLE_PEN_SLOT("즐겨찾기 펜 슬롯 순환"),
    HANDWRITING_KEYBOARD("필기 키보드 변환 입력"),
    ERASE("지우개 (누르는 동안)"),
    UNDO("실행 취소 (Undo)"),
    REDO("다시 실행 (Redo)"),
    OPEN_MEMO("메모장 열기/올리기"),
    TOGGLE_GESTURE_MODE("제스처 모드 전환"),
    TOGGLE_HIGHLIGHTER("형광펜 전환"),
    TOGGLE_STRAIGHT_LINE("직선 모드 전환"),
    NONE("사용 안 함")
}

object DefaultGestureMappings {
    val defaults: Map<GestureTrigger, GestureAction> = mapOf(
        GestureTrigger.PEN_DOUBLE_TAP to GestureAction.SWAP_PEN_ERASER,
        GestureTrigger.BARREL_PRIMARY_HOLD to GestureAction.ERASE,
        GestureTrigger.BARREL_SECONDARY_PRESS to GestureAction.PREVIOUS_TOOL,
        GestureTrigger.SCRIBBLE to GestureAction.ERASE,
        GestureTrigger.TWO_FINGER_TAP to GestureAction.UNDO,
        GestureTrigger.TWO_FINGER_SWIPE_UP to GestureAction.OPEN_MEMO,
        GestureTrigger.THREE_FINGER_TAP to GestureAction.REDO,
        GestureTrigger.THREE_FINGER_SWIPE_UP to GestureAction.NONE
    )
}
