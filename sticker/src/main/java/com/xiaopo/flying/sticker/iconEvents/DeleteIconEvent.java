package com.xiaopo.flying.sticker.iconEvents;

import android.view.MotionEvent;

import com.xiaopo.flying.sticker.StickerIconEvent;
import com.xiaopo.flying.sticker.StickerView;
import com.xiaopo.flying.sticker.StickerViewModel;

/**
 * Deletes the item on tap; deleting is undoable, so no long press is needed.
 *
 * @author wupanjie
 */

public class DeleteIconEvent implements StickerIconEvent {
    @Override
    public void onActionDown(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {

    }

    @Override
    public void onActionMove(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {

    }

    @Override
    public void onActionUp(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {
        viewModel.removeCurrentSticker();
    }

    @Override
    public void onActionLongPress(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {
        viewModel.removeCurrentSticker();
    }
}
