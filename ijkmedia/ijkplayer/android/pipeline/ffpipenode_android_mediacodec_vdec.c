/*
 * ffpipenode_android_mediacodec_vdec.c
 *
 * Copyright (c) 2014 Zhang Rui <bbcallen@gmail.com>
 *
 * This file is part of ijkPlayer.
 *
 * ijkPlayer is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * ijkPlayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with ijkPlayer; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "ffpipenode_android_mediacodec_vdec.h"
#include "../../ff_ffpipenode.h"
#include "../../ff_ffplay.h"
#include "ijksdl/android/ijksdl_android_jni.h"
#include "ijksdl/android/ijksdl_codec_android_mediaformat_java.h"
#include "ijksdl/android/ijksdl_codec_android_mediacodec_java.h"

typedef struct IJKFF_Pipenode_Opaque {
    IJKFF_Pipeline           *pipeline;
    FFPlayer                 *ffp;
    Decoder                  *decoder;

    SDL_AMediaFormat         *aformat;
    SDL_AMediaCodec          *acodec;

    AVCodecContext           *avctx; // not own
    AVBitStreamFilterContext *bsfc;  // own

    uint8_t                  *orig_extradata;
    int                       orig_extradata_size;

    int                       abort_request;
} IJKFF_Pipenode_Opaque;


static void func_destroy(IJKFF_Pipenode *node)
{
    if (!node || !node->opaque)
        return;

    IJKFF_Pipenode_Opaque *opaque = node->opaque;
    SDL_AMediaCodec_deleteP(&opaque->acodec);
    SDL_AMediaFormat_deleteP(&opaque->aformat);

    av_freep(&opaque->orig_extradata);

    if (opaque->bsfc) {
        av_bitstream_filter_close(opaque->bsfc);
        opaque->bsfc = NULL;
    }
}

static int func_run_sync(IJKFF_Pipenode *node)
{
    IJKFF_Pipenode_Opaque *opaque = node->opaque;

    return ffp_video_thread(opaque->ffp);
}

IJKFF_Pipenode *ffpipenode_create_video_decoder_from_android_mediacodec(FFPlayer *ffp)
{
    ALOGD("ffpipenode_create_video_decoder_from_android_mediacodec()\n");
    if (SDL_Android_GetApiLevel() < IJK_API_16_JELLY_BEAN)
        return NULL;

    if (!ffp || !ffp->is)
        return NULL;

    IJKFF_Pipenode *node = ffpipenode_alloc(sizeof(IJKFF_Pipenode_Opaque));
    if (!node)
        return node;

    VideoState            *is         = ffp->is;
    IJKFF_Pipenode_Opaque *opaque     = node->opaque;
    const char            *codec_mime = NULL;
    JNIEnv                *env        = NULL;

    node->func_destroy  = func_destroy;
    node->func_run_sync = func_run_sync;
    opaque->ffp         = ffp;
    opaque->decoder     = &is->viddec;

    opaque->avctx = opaque->decoder->avctx;
    switch (opaque->avctx->codec_id) {
    case AV_CODEC_ID_H264:
        codec_mime = SDL_AMIME_VIDEO_AVC;
        break;
    default:
        ALOGE("amediacodec-pipeline:open_video_decoder: not H264\n");
        goto fail;
    }

    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("amediacodec-pipeline:open_video_decoder: SetupThreadEnv failed\n");
        goto fail;
    }

    opaque->acodec = SDL_AMediaCodecJava_createDecoderByType(env, codec_mime);
    if (!opaque->acodec) {
        ALOGE("amediacodec-pipeline:open_video_decoder: SDL_AMediaCodecJava_createDecoderByType failed\n");
        goto fail;
    }

    opaque->aformat = SDL_AMediaFormatJava_createVideoFormat(env, codec_mime, opaque->avctx->width, opaque->avctx->height);
    if (opaque->avctx->extradata && opaque->avctx->extradata_size > 0) {
        if (opaque->avctx->codec_id == AV_CODEC_ID_H264 && opaque->avctx->extradata[0] == 1) {
            opaque->bsfc = av_bitstream_filter_init("h264_mp4toannexb");

            opaque->orig_extradata_size = opaque->avctx->extradata_size + FF_INPUT_BUFFER_PADDING_SIZE;
            opaque->orig_extradata      = (uint8_t*) av_mallocz(opaque->orig_extradata_size);
            if (!opaque->orig_extradata) {
                ALOGE("amediacodec-pipeline:open_video_decoder: orig_extradata alloc failed\n");
                goto fail;
            }
            memcpy(opaque->orig_extradata, opaque->avctx->extradata, opaque->avctx->extradata_size);
            SDL_AMediaFormat_setBuffer(opaque->aformat, "csd-0", opaque->orig_extradata, opaque->orig_extradata_size);
        } else {
            // Codec specific data
            SDL_AMediaFormat_setBuffer(opaque->aformat, "csd-0", opaque->avctx->extradata, opaque->avctx->extradata_size);
        }
    }

    return node;
fail:
    ffpipenode_free_p(&node);
    return NULL;
}
