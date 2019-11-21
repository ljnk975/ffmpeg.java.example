package ffmpeg.examples;

/*
 * Copyright (c) 2003 Fabrice Bellard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
/**
 * @file
 * libavformat API example.
 *
 * Output a media file in any supported libavformat format.
 * The default codecs are used.
 * @example doc/examples/muxing.c
 */
import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.presets.avutil.AVERROR_EAGAIN;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.swscale.*;
import static org.bytedeco.javacpp.swresample.*;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.avcodec.AVCodec;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.bytedeco.javacpp.avutil.AVFrame;
import org.bytedeco.javacpp.avutil.AVRational;
import org.bytedeco.javacpp.swresample.SwrContext;

public class Muxing {

	static final int AVFMT_RAWPICTURE = 0x0020;
	
	/* 5 seconds stream duration */
	static final int    STREAM_DURATION   = 10;
	static final int    STREAM_FRAME_RATE = 25; /* 25 images/s */
	static final int    STREAM_NB_FRAMES  = STREAM_DURATION * STREAM_FRAME_RATE;
	static final int    STREAM_PIX_FMT    = AV_PIX_FMT_YUV420P; /* default pix_fmt */
	static final int    sws_flags         = SWS_BICUBIC;

	// a wrapper around a single output AVStream
	static class AVOutputStream {
	    AVStream st;
	    AVCodec codec;
	    AVCodecContext enc;

	    /* pts of the next frame that will be generated */
	    long next_pts;
	    int samples_count;

	    AVFrame frame;
	    AVFrame tmp_frame;

	    float t, tincr, tincr2;

	    SwsContext sws_ctx;
	    SwrContext swr_ctx;
	};

	static String av_ts2str(long ts) {
//		BytePointer data = new BytePointer(AV_TS_MAX_STRING_SIZE);
//		av_ts_make_string(data, ts);
//		return data.getString();
		return ""+ts;
	}

	static String av_ts2timestr(long ts, AVRational tb) {
//		BytePointer data = new BytePointer(AV_TS_MAX_STRING_SIZE);
//		av_ts_make_time_string(data, ts, tb);
//		return data.getString();
		return ""+(ts*av_q2d(tb));
	}

	static void log_packet(AVFormatContext fmt_ctx, AVPacket pkt) {
	    AVRational time_base = fmt_ctx.streams(pkt.stream_index()).time_base();

	    System.out.format("stream_index:%d pts:%s pts_time:%s dts:%s dts_time:%s duration:%s duration_time:%s\n",
	    		pkt.stream_index(),
	    		av_ts2str(pkt.pts()), av_ts2timestr(pkt.pts(), time_base),
	    		av_ts2str(pkt.dts()), av_ts2timestr(pkt.dts(), time_base),
	    		av_ts2str(pkt.duration()), av_ts2timestr(pkt.duration(), time_base)
	           );
	}

	static int write_frame(AVFormatContext fmt_ctx, AVRational time_base, AVStream st, AVPacket pkt) {
	    /* rescale output packet timestamp values from codec to stream timebase */
	    av_packet_rescale_ts(pkt, time_base, st.time_base());
	    pkt.stream_index(st.index());

	    /* Write the compressed frame to the media file. */
	    log_packet(fmt_ctx, pkt);
	    return av_interleaved_write_frame(fmt_ctx, pkt);
	}

	/* just pick the highest supported samplerate */
	static int select_sample_rate(AVCodec codec) {
	    IntPointer p;
	    
	    int best_samplerate = 0;

	    if (codec.supported_samplerates().isNull())
	        return 44100;

	    p = codec.supported_samplerates(); int i = 0;
	    while (p.get(i) != 0) {
	        if (best_samplerate == 0 || Math.abs(44100 - p.get(i)) < Math.abs(44100 - best_samplerate))
	            best_samplerate = p.get();
	        i++;
	    }

	    return best_samplerate;
	}

	/* select layout with the highest channel count */
	static long select_channel_layout(AVCodec codec) {
	    LongPointer p;

	    long best_ch_layout = 0;
	    int best_nb_channels   = 0;

	    if (codec.channel_layouts() == null)
	        return AV_CH_LAYOUT_STEREO;

	    p = codec.channel_layouts(); int i = 0;
	    while (p.get(i) != 0) {
	        int nb_channels = av_get_channel_layout_nb_channels(p.get());
	        if (nb_channels > best_nb_channels) {
	            best_ch_layout   = p.get();
	            best_nb_channels = nb_channels;
	        }
	        i++;
	    }
	    return best_ch_layout;
	}

	/* Add an output stream. */
	static AVOutputStream add_stream(AVFormatContext oc, int width, int height, int frame_rate, int fix_fmt, int codec_id) {
		int ret;

		AVOutputStream ost = new AVOutputStream();

		/* find the encoder */
		AVCodec codec = avcodec_find_encoder(codec_id);
		if (codec.isNull()) {
			System.out.println("Could not find encoder for " + avcodec_get_name(codec_id));
			System.exit(1);
		}
		ost.codec = codec;

		System.out.println("Add stream, codec="+codec.name().getString());

		/* New stream */
		AVStream st = avformat_new_stream(oc, codec);
		if (st == null) {
			System.out.println("Could not allocate stream");
			System.exit(1);
		}
		ost.st = st;

		/* Open Codec Context */
		AVCodecContext c;
	    if ((c = avcodec_alloc_context3(codec)).isNull()) {
	    	System.out.println("Failed to allocate codec context");
	    	System.exit(1);
	    }
	    ost.enc = c;

		st.id(oc.nb_streams() - 1);
		switch (codec.type()) {
			case AVMEDIA_TYPE_AUDIO:
				/* put sample parameters */
			    c.bit_rate(64000);

			    /* check that the encoder supports s16 pcm input */
			    c.sample_fmt(AV_SAMPLE_FMT_FLTP);

			    /* select other audio parameters supported by the encoder */
			    c.sample_rate(select_sample_rate(codec));
			    c.channel_layout(select_channel_layout(codec));
			    c.channels(av_get_channel_layout_nb_channels(c.channel_layout()));
				break;

			case AVMEDIA_TYPE_VIDEO:
				c.bit_rate(400000);

				/* Resolution must be a multiple of two. */
				c.width(width);
				c.height(height);

				/*
				 * timebase: This is the fundamental unit of time (in seconds) in terms of which
				 * frame timestamps are represented. For fixed-fps content, timebase should be
				 * 1/framerate and timestamp increments should be identical to 1.
				 */
				c.time_base().den(frame_rate);
				c.time_base().num(1);
				c.gop_size(12); /* emit one intra frame every twelve frames at most */
				c.pix_fmt(fix_fmt);

				if (c.codec_id() == AV_CODEC_ID_MPEG2VIDEO) {
					/* just for testing, we also add B frames */
					c.max_b_frames(2);
				}

				if (c.codec_id() == AV_CODEC_ID_MPEG1VIDEO) {
					/*
					 * Needed to avoid using macroblocks in which some coeffs overflow. This does
					 * not happen with normal video, it just happens here as the motion of the
					 * chroma plane does not match the luma plane.
					 */
					c.mb_decision(2);
				}

				if (c.codec_id() == AV_CODEC_ID_H264)
			        av_opt_set(c, "preset", "ultrafast", 0);
				break;
		}

		/* Some formats want stream headers to be separate. */
		if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) > 0)
			c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);

	    /* open it */
	    ret = avcodec_open2(c, codec, new AVDictionary(null));
	    if (ret < 0) {
	    	System.out.println("Could not open stream codec: "+av_err2str(ret));
	    	System.exit(1);
	    }

	    /* copy the stream parameters to the muxer */
	    ret = avcodec_parameters_from_context(ost.st.codecpar(), c);
	    if (ret < 0) {
	    	System.out.println("Could not copy the stream parameters\n");
	    	System.exit(1);
	    }

	    return ost;
	}

	static AVOutputStream add_video_stream(AVFormatContext oc, int width, int height, int frame_rate, int fix_fmt, int codec_id) {
		return add_stream(oc, width, height, frame_rate, fix_fmt, codec_id);
	}

	static AVOutputStream add_audio_stream(AVFormatContext oc, int codec_id) {
		return add_stream(oc, 0, 0, 0, 0, codec_id);
	}

	static String av_err2str(int errnum) {
		BytePointer data = new BytePointer(new byte[AV_ERROR_MAX_STRING_SIZE]);
		av_make_error_string(data, AV_ERROR_MAX_STRING_SIZE, errnum);
		return data.getString();
	}

	/**************************************************************/
	/* audio output */
	static AVFrame alloc_audio_frame(int sample_fmt, long channel_layout, int sample_rate, int nb_samples) {
		AVFrame frame = av_frame_alloc();

		int ret;

		if (frame.isNull()) {
			System.out.println("Error allocating an audio frame\n");
			System.exit(1);
		}

		frame.format(sample_fmt);
		frame.channel_layout(channel_layout);
		frame.sample_rate(sample_rate);
		frame.nb_samples(nb_samples);

		if (nb_samples != 0) {
			ret = av_frame_get_buffer(frame, 0);
			if (ret < 0) {
				System.out.println("Error allocating an audio buffer\n");
				System.exit(1);
			}
		}

		return frame;
	}

	static void open_audio(AVFormatContext oc, AVOutputStream ost) {
		int ret;

		AVCodecContext c = ost.enc;

	    int nb_samples;

	    /* init signal generator */
	    ost.t     = 0;
	    ost.tincr = (float) (2 * M_PI * 110.0 / c.sample_rate());
	    /* increment frequency by 110 Hz per second */
	    ost.tincr2 = (float) (2 * M_PI * 110.0 / c.sample_rate() / c.sample_rate());

	    if ((c.codec().capabilities() & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) > 0)
	        nb_samples = 10000;
	    else
	        nb_samples = c.frame_size();

	    ost.frame     = alloc_audio_frame(c.sample_fmt(), c.channel_layout(), c.sample_rate(), nb_samples);
	    ost.tmp_frame = alloc_audio_frame(AV_SAMPLE_FMT_S16, c.channel_layout(), c.sample_rate(), nb_samples);

	    /* create resampler context */
        ost.swr_ctx = swr_alloc();

        if (ost.swr_ctx.isNull()) {
        	System.out.println("Could not allocate resampler context\n");
        	System.exit(1);
        }

        /* set options */
        av_opt_set_int       (ost.swr_ctx, "in_channel_count",   c.channels(),       0);
        av_opt_set_int       (ost.swr_ctx, "in_sample_rate",     c.sample_rate(),    0);
        av_opt_set_sample_fmt(ost.swr_ctx, "in_sample_fmt",      AV_SAMPLE_FMT_S16, 0);
        av_opt_set_int       (ost.swr_ctx, "out_channel_count",  c.channels(),       0);
        av_opt_set_int       (ost.swr_ctx, "out_sample_rate",    c.sample_rate(),    0);
        av_opt_set_sample_fmt(ost.swr_ctx, "out_sample_fmt",     c.sample_fmt(),     0);

        /* initialize the resampling context */
        if ((ret = swr_init(ost.swr_ctx)) < 0) {
        	System.out.println("Failed to initialize the resampling context: "+av_err2str(ret));
        	System.exit(1);
        }
	}

	/* Prepare a 16 bit dummy audio frame of 'frame_size' samples and
	 * 'nb_channels' channels. */
	static AVFrame get_audio_frame(AVOutputStream ost) {
	    AVFrame frame = ost.tmp_frame;
	    int j, i, v; int k = 0;

	    /* check if we want to generate more frames */
	    AVRational r = new AVRational(); r.num(1); r.den(1);
	    if (av_compare_ts(ost.next_pts, ost.enc.time_base(), STREAM_DURATION, r) > 0)
	        return null;

        for (j = 0; j < frame.nb_samples(); j++) {
	        v = (int)(Math.sin(ost.t) * 10000);
	        for (i = 0; i < ost.enc.channels(); i++) {
	        	frame.data(0).put(k++, (byte) v);
	        	frame.data(0).put(k++, (byte) (v >> 8));
	        	frame.data(0).put(k++, (byte) (v >> 16));
	        	frame.data(0).put(k++, (byte) (v >> 24));
	        }
	        ost.t     += ost.tincr;
	        ost.tincr += ost.tincr2;
	    }

	    frame.pts(ost.next_pts);
	    ost.next_pts  += frame.nb_samples();

	    return frame;
	}
	
	/*
	 * encode one audio frame and send it to the muxer
	 * return 1 when encoding is finished, 0 otherwise
	 */
	static boolean write_audio_frame(AVFormatContext oc, AVOutputStream ost) {
	    int ret;

	    AVCodecContext c = ost.enc;

	    int dst_nb_samples;

	    AVFrame frame = get_audio_frame(ost);

	    if (frame != null) {
	        /* convert samples from native format to destination codec format, using the resampler */
	    	/* compute destination number of samples */
	    	dst_nb_samples = (int) av_rescale_rnd(swr_get_delay(ost.swr_ctx, c.sample_rate()) + frame.nb_samples(), c.sample_rate(), c.sample_rate(), AV_ROUND_UP);

	        /* when we pass a frame to the encoder, it may keep a reference to it
	         * internally;
	         * make sure we do not overwrite it here
	         */
	        ret = av_frame_make_writable(ost.frame);
	        if (ret < 0)
	        	System.exit(1);

	        /* convert to destination format */
	        ret = swr_convert(ost.swr_ctx, ost.frame.data(), dst_nb_samples, frame.data(), frame.nb_samples());
	        if (ret < 0) {
	        	System.out.println("Error while converting");
	        	System.exit(1);
	        }

	        frame = ost.frame;

	        AVRational r = new AVRational(); r.num(1); r.den(c.sample_rate());
	        frame.pts(av_rescale_q(ost.samples_count, r, c.time_base()));
	        ost.samples_count += dst_nb_samples;
	    }

		/* encode the image */
	    ret = avcodec_send_frame(c, frame);
        if (ret < 0) {
        	System.out.println("Failed to send frame: "+av_err2str(ret));
        	System.exit(1);
        }

        AVPacket pkt = new AVPacket();
        av_init_packet(pkt);
        pkt.data(null);
        pkt.size(0);

        while (ret >= 0) {
            ret = avcodec_receive_packet(c, pkt);
           
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                break;

    	    if (ret < 0) {
    	    	System.out.println("Error during encoding: "+av_err2str(ret));
	        	System.exit(1);
    	    }

    	    /* Write the compressed frame to the media file. */
    	    write_frame(oc, c.time_base(), ost.st, pkt);

            av_packet_unref(pkt);
        }

	    return frame == null;
	}

	/**************************************************************/
	/* video output */
	static AVFrame alloc_picture(int pix_fmt, int width, int height) {
	    AVFrame picture;
	    int ret;

	    picture = av_frame_alloc();
	    if (picture.isNull())
	        return null;

	    picture.format(pix_fmt);
	    picture.width(width);
	    picture.height(height);

	    /* allocate the buffers for the frame data */
	    ret = av_frame_get_buffer(picture, 32);
	    if (ret < 0) {
	    	System.out.println("Could not allocate frame data.\n");
	    	System.exit(1);
	    }

	    return picture;
	}

	static void open_video(AVFormatContext oc, AVOutputStream ost) {
	    AVCodecContext c = ost.enc;

	    /* allocate and init a re-usable frame */
	    ost.frame = alloc_picture(c.pix_fmt(), c.width(), c.height());
	    if (ost.frame.isNull()) {
	    	System.out.println("Could not allocate video frame");
	    	System.exit(1);
	    }

	    /* If the output format is not YUV420P, then a temporary YUV420P
	     * picture is needed too. It is then converted to the required
	     * output format. */
	    ost.tmp_frame = null;
	    if (c.pix_fmt() != AV_PIX_FMT_YUV420P) {
	        ost.tmp_frame = alloc_picture(AV_PIX_FMT_YUV420P, c.width(), c.height());
	        if (ost.tmp_frame.isNull()) {
	        	System.out.println("Could not allocate temporary picture");
	        	System.exit(1);
	        }
	    }
	}

	/* Prepare a dummy image. */
	static void fill_yuv_image(AVFrame pict, long frame_index, int width, int height) {
	    int x, y;

	    long i = frame_index;

	    /* Y */
	    for (y = 0; y < height; y++)
	        for (x = 0; x < width; x++)
	            pict.data(0).put(y * pict.linesize(0) + x, (byte) (x + y + i * 3));

	    /* Cb and Cr */
	    for (y = 0; y < height / 2; y++) {
	        for (x = 0; x < width / 2; x++) {
	            pict.data(1).put(y * pict.linesize(1) + x, (byte) (128 + y + i * 2));
	            pict.data(2).put(y * pict.linesize(2) + x, (byte) (64 + x + i * 5));
	        }
	    }
	}

	static AVFrame get_video_frame(AVOutputStream ost) {
	    AVCodecContext c = ost.enc;

	    /* check if we want to generate more frames */
	    AVRational r = new AVRational(); r.num(1); r.den(1);
	    if (av_compare_ts(ost.next_pts, c.time_base(), STREAM_DURATION, r) > 0)
	        return null;

	    /* when we pass a frame to the encoder, it may keep a reference to it
	     * internally; make sure we do not overwrite it here */
	    if (av_frame_make_writable(ost.frame) < 0)
	    	System.exit(1);

	    if (c.pix_fmt() != AV_PIX_FMT_YUV420P) {
	        /* as we only generate a YUV420P picture, we must convert it
	         * to the codec pixel format if needed */
	        if (ost.sws_ctx == null) {
	            ost.sws_ctx = sws_getContext(c.width(), c.height(),
	                                          AV_PIX_FMT_YUV420P,
	                                          c.width(), c.height(),
	                                          c.pix_fmt(),
	                                          sws_flags, null, null, (DoublePointer) null);
	            if (ost.sws_ctx.isNull()) {
	            	System.out.println("Could not initialize the conversion context\n");
	            	System.exit(1);
	            }
	        }
	        fill_yuv_image(ost.tmp_frame, ost.next_pts, c.width(), c.height());
	        sws_scale(ost.sws_ctx, ost.tmp_frame.data(),
	                  ost.tmp_frame.linesize(), 0, c.height(), ost.frame.data(),
	                  ost.frame.linesize());
	    } else
	        fill_yuv_image(ost.frame, ost.next_pts, c.width(), c.height());

	    ost.frame.pts(ost.next_pts++);
	    return ost.frame;
	}

	/*
	 * encode one video frame and send it to the muxer
	 * return 1 when encoding is finished, 0 otherwise
	 */
	static boolean write_video_frame(AVFormatContext oc, AVOutputStream ost) {
	    int ret;

	    AVCodecContext c = ost.enc;

	    AVFrame frame = get_video_frame(ost);

		/* encode the image */
	    ret = avcodec_send_frame(c, frame);
        if (ret < 0) {
        	System.out.println("Failed to send frame: "+av_err2str(ret));
        	System.exit(1);
        }

        AVPacket pkt = new AVPacket();
        av_init_packet(pkt);
        pkt.data(null);
        pkt.size(0);

        while (ret >= 0) {
            ret = avcodec_receive_packet(c, pkt);
           
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                break;

    	    if (ret < 0) {
    	    	System.out.println("Error during encoding: "+av_err2str(ret));
	        	System.exit(1);
    	    }

    	    /* Write the compressed frame to the media file. */
    	    write_frame(oc, c.time_base(), ost.st, pkt);

            av_packet_unref(pkt);
        }

	    return frame == null;
	}

	static void close_stream(AVFormatContext oc, AVOutputStream ost) {
	    avcodec_free_context(ost.enc);
	    av_frame_free(ost.frame);
	    av_frame_free(ost.tmp_frame);
	    sws_freeContext(ost.sws_ctx);
	    swr_free(ost.swr_ctx);
	}

	/**************************************************************/
	/* media file output */
	public static void main(String[] args) {
	    String filename = "Test.mp4"; int width = 640; int height = 480;

	    int ret;

	    AVOutputFormat fmt;
	    AVFormatContext oc = new AVFormatContext();

	    AVOutputStream video_st, audio_st;
	    
	    /* allocate the output media context */
	    avformat_alloc_output_context2(oc, null, null, filename);
	    if (oc.isNull()) {
	        System.out.println("Could not deduce output format from file extension: using MPEG.");
	        avformat_alloc_output_context2(oc, null, "mpeg", filename);
	    }
	    if (oc.isNull())
	    	System.exit(1);
	    fmt = oc.oformat();

	    /* Add the audio and video streams using the default format codecs
	     * and initialize the codecs. */
	    video_st = null;
	    audio_st = null;

	    if (fmt.video_codec() != AV_CODEC_ID_NONE)
	        video_st = add_video_stream(oc, width, height, STREAM_NB_FRAMES, STREAM_PIX_FMT, fmt.video_codec());
	    if (fmt.audio_codec() != AV_CODEC_ID_NONE)
	        audio_st = add_audio_stream(oc, fmt.audio_codec());

	    /* Now that all the parameters are set, we can open the audio and
	     * video codecs and allocate the necessary encode buffers. */
	    if (video_st != null)
	        open_video(oc, video_st);
	    if (audio_st != null)
	        open_audio(oc, audio_st);
	    av_dump_format(oc, 0, filename, 1);

	    /* open the output file, if needed */
	    if ((fmt.flags() & AVFMT_NOFILE) == 0) {
	    	AVIOContext pb = new AVIOContext(null);
	        ret = avio_open(pb, filename, AVIO_FLAG_WRITE);
	        if (ret < 0) {
	            System.out.println("Could not open file: "+av_err2str(ret));
	            System.exit(1);
	        }
	        oc.pb(pb);
	    }

	    /* Write the stream header, if any. */
	    ret = avformat_write_header(oc, new AVDictionary(null));
	    if (ret < 0) {
	        System.out.println("Error occurred when opening output file: "+av_err2str(ret));
	        System.exit(1);
	    }

	    boolean encode_video = video_st != null;
	    boolean encode_audio = audio_st != null;
	    
	    while (encode_video || encode_audio) {
	    	/* select the stream to encode */
	        if (encode_video &&
	            (!encode_audio || av_compare_ts(video_st.next_pts, video_st.enc.time_base(),
	                                            audio_st.next_pts, audio_st.enc.time_base()) <= 0)) {
	            encode_video = !write_video_frame(oc, video_st);
	        } else
	            encode_audio = !write_audio_frame(oc, audio_st);
	    }

	    /* Write the trailer, if any. The trailer must be written before you
	     * close the CodecContexts open when you wrote the header; otherwise
	     * av_write_trailer() may try to use memory that was freed on
	     * av_codec_close(). */
	    av_write_trailer(oc);

	    /* Close each codec. */
	    if (video_st != null)
	        close_stream(oc, video_st);

	    if (audio_st != null)
	        close_stream(oc, audio_st);

	    if ((fmt.flags() & AVFMT_NOFILE) > 0)
	        /* Close the output file. */
	        avio_close(oc.pb());

	    /* free the stream */
	    avformat_free_context(oc);
	}

}
