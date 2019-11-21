package ffmpeg.examples;

import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avcodec.*;

import java.io.FileOutputStream;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil.AVFrame;

public class Demuxing_Decoding {

	static AVFormatContext fmt_ctx = new AVFormatContext(null);
	static AVCodecContext video_dec_ctx = new AVCodecContext(null);
	static AVCodecContext audio_dec_ctx = new AVCodecContext(null);
	static int width, height;
	static int pix_fmt;
	static AVStream video_stream = null, audio_stream = null;
	static String src_filename = null;
	static String video_dst_filename = null;
	static String audio_dst_filename = null;

	static FileOutputStream video_dst_file = null;
	static FileOutputStream audio_dst_file = null;

	static PointerPointer<BytePointer> video_dst_data = new PointerPointer<BytePointer>(new BytePointer(), new BytePointer(), new BytePointer(), new BytePointer());
	static IntPointer video_dst_linesize = new IntPointer(4);
	static int video_dst_bufsize;

	static AVFrame frame = null;
	static int video_frame_count = 0;
	static int audio_frame_count = 0;

	static String av_err2str(int errnum) {
		BytePointer data = new BytePointer(new byte[AV_ERROR_MAX_STRING_SIZE]);
		av_make_error_string(data, AV_ERROR_MAX_STRING_SIZE, errnum);
		return data.getString();
	}

	static void decode_packet(AVPacket pkt, boolean cached) throws Exception {
	    int ret = 0;

        if (pkt.stream_index() == video_stream.index()) {
    	    /* send the packet with the compressed data to the decoder */
    	    ret = avcodec_send_packet(video_dec_ctx, pkt);
    	    if (ret < 0)
    	        throw new Exception("Error submitting the packet to the decoder\n");

    	    /* read all the output frames (in general there may be any number of them */
    	    while (ret >= 0) {
    	        ret = avcodec_receive_frame(video_dec_ctx, frame);

    	        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
    	        	return;

    	        if (ret < 0)
    	            throw new Exception("Error during decoding\n");

	            if (frame.width() != width || frame.height() != height || frame.format() != pix_fmt)
	                /* To handle this change, one could call av_image_alloc again and
	                 * decode the following frames into another rawvideo file. */
	            	throw new Exception(String.format("Error: Width, height and pixel format have to be "
	                        + "constant in a rawvideo file, but the width, height or "
	                        + "pixel format of the input video changed:\n"
	                        + "old: width = %d, height = %d, format = %s\n"
	                        + "new: width = %d, height = %d, format = %s\n",
	                        width, height, av_get_pix_fmt_name(pix_fmt),
	                        frame.width(), frame.height(),
	                        av_get_pix_fmt_name(frame.format())));

	            System.out.format("video_frame%s n:%d coded_n:%d\n",
	                   cached ? "(cached)" : "",
	                   video_frame_count++, frame.coded_picture_number());

	            /* copy decoded frame to destination buffer:
	             * this is required since rawvideo expects non aligned data */
	            av_image_copy(video_dst_data, video_dst_linesize, frame.data(), frame.linesize(), pix_fmt, width, height);

	            /* write to rawvideo file */
	            for(int i = 0; i < video_dst_bufsize; i++)
	            	video_dst_file.write(video_dst_data.get(BytePointer.class, 0).get(i));
    	    }
        } else if (pkt.stream_index() == audio_stream.index()) {
    	    /* send the packet with the compressed data to the decoder */
    	    ret = avcodec_send_packet(audio_dec_ctx, pkt);
    	    if (ret < 0)
    	        throw new Exception("Error submitting the packet to the decoder\n");

    	    /* read all the output frames (in general there may be any number of them */
    	    while (ret >= 0) {
    	        ret = avcodec_receive_frame(audio_dec_ctx, frame);

    	        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
    	        	return;

    	        if (ret < 0)
    	            throw new Exception("Error during decoding\n");

	            int unpadded_linesize = frame.nb_samples() * av_get_bytes_per_sample(frame.format());
	            System.out.format("audio_frame%s n:%d nb_samples:%d pts:%d\n",
	                   cached ? "(cached)" : "",
	                   audio_frame_count++, frame.nb_samples(), frame.pts());
//	                   av_ts2timestr(frame.pts(), audio_dec_ctx.time_base()));

	            /* Write the raw audio data samples of the first plane. This works
	             * fine for packed formats (e.g. AV_SAMPLE_FMT_S16). However,
	             * most audio decoders output planar audio, which uses a separate
	             * plane of audio samples for each channel (e.g. AV_SAMPLE_FMT_S16P).
	             * In other words, this code will write only the first audio channel
	             * in these cases.
	             * You should use libswresample or libavfilter to convert the frame
	             * to packed data. */
	            for(int i = 0; i < unpadded_linesize; i++)
	            	audio_dst_file.write(frame.extended_data(0).get(i));
    	    }
        }
	}

	static AVStream open_stream(AVFormatContext fmt_ctx, int type) throws Exception {
	    int stream_index;

	    stream_index = av_find_best_stream(fmt_ctx, type, -1, -1, (AVCodec) null, 0);
	    if (stream_index < 0)
	    	throw new Exception(String.format("Could not find %s stream in input file '%s'\n",
	                av_get_media_type_string(type), src_filename));

        return fmt_ctx.streams(stream_index);
	}

	static AVCodecContext open_codec_context(AVStream st, int type) throws Exception {
	    AVCodec dec = null;

        /* find decoder for the stream */
        dec = avcodec_find_decoder(st.codecpar().codec_id());
        if (dec.isNull())
        	throw new Exception(String.format("Failed to find %s codec\n", av_get_media_type_string(type)));

        /* Allocate a codec context for the decoder */
        AVCodecContext dec_ctx = avcodec_alloc_context3(dec);
        if (dec_ctx.isNull())
        	throw new Exception(String.format("Failed to allocate the %s codec context\n", av_get_media_type_string(type)));

	    /* Copy codec parameters from input stream to output codec context */
        if (avcodec_parameters_to_context(dec_ctx, st.codecpar()) < 0)
        	throw new Exception(String.format("Failed to copy %s codec parameters to decoder context\n", av_get_media_type_string(type)));

        /* Init the decoders, with or without reference counting */
        if (avcodec_open2(dec_ctx, dec, (AVDictionary) null) < 0)
        	throw new Exception(String.format("Failed to open %s codec\n", av_get_media_type_string(type)));

        return dec_ctx;
	}

	static class sample_fmt_entry {
        int sample_fmt;
        String fmt_be, fmt_le;

        sample_fmt_entry(int sample_fmt, String fmt_be, String fmt_le) {
        	this.sample_fmt = sample_fmt;
        	this.fmt_be = fmt_be;
        	this.fmt_le = fmt_le;
        }
	}

	static String get_format_from_sample_fmt(int sample_fmt) {
	    int i;
	    sample_fmt_entry[] sample_fmt_entries = {
	        new sample_fmt_entry(AV_SAMPLE_FMT_U8,  "u8",    "u8"),
	        new sample_fmt_entry(AV_SAMPLE_FMT_S16, "s16be", "s16le"),
    		new sample_fmt_entry(AV_SAMPLE_FMT_S32, "s32be", "s32le"),
    		new sample_fmt_entry(AV_SAMPLE_FMT_FLT, "f32be", "f32le"),
    		new sample_fmt_entry(AV_SAMPLE_FMT_DBL, "f64be", "f64le")
	    };

	    for (i = 0; i < sample_fmt_entries.length; i++) {
	        sample_fmt_entry entry = sample_fmt_entries[i];
	        if (sample_fmt == entry.sample_fmt) {
	            return entry.fmt_le;
	        }
	    }

	    System.out.format(
	            "sample format %s is not supported as output format\n",
	            av_get_sample_fmt_name(sample_fmt));
	    return null;
	}

	public static void main(String[] args) throws Exception {
	    int ret = 0;

	    src_filename = "UntilYou-ShayneWard_6xrx.mp4";

	    video_dst_filename = "Test.mp4";
	    audio_dst_filename = "Test.mp3";

	    /* open input file, and allocate format context */
	    if (avformat_open_input(fmt_ctx, src_filename, null, null) < 0) {
	        System.out.format("Could not open source file %s\n", src_filename);
	        System.exit(1);
	    }
	    
	    /* retrieve stream information */
	    if (avformat_find_stream_info(fmt_ctx, (AVDictionary) null) < 0) {
	    	System.out.format("Could not find stream information\n");
	        System.exit(1);
	    }

	    try {
	    	video_stream  = open_stream(fmt_ctx, AVMEDIA_TYPE_VIDEO);
	    	video_dec_ctx = open_codec_context(video_stream, AVMEDIA_TYPE_VIDEO);

	        video_dst_file = new FileOutputStream(video_dst_filename);
	        if (video_dst_file == null) {
	        	System.out.format("Could not open destination file %s\n", video_dst_filename);
	        	System.exit(1);
	        }

	        /* allocate image where the decoded image will be put */
	        width = video_dec_ctx.width();
	        height = video_dec_ctx.height();
	        pix_fmt = video_dec_ctx.pix_fmt();
	        ret = av_image_alloc(video_dst_data, video_dst_linesize, width, height, pix_fmt, 1);
	        if (ret < 0) {
	        	System.out.format("Could not allocate raw video buffer\n");
	        	System.exit(1);
	        }

	        video_dst_bufsize = ret;
	    } catch(Exception e) {
	    	e.printStackTrace();
	    }

	    try {
	        audio_stream  = open_stream(fmt_ctx, AVMEDIA_TYPE_AUDIO);
	        audio_dec_ctx = open_codec_context(audio_stream, AVMEDIA_TYPE_AUDIO);
	        audio_dst_file = new FileOutputStream(audio_dst_filename);
	        if (audio_dst_file == null) {
	        	System.out.format("Could not open destination file %s\n", audio_dst_filename);
	        	System.exit(1);
	        }
	    } catch(Exception e) {
	    	e.printStackTrace();
	    }

	    try {
		    /* dump input information to stderr */
		    av_dump_format(fmt_ctx, 0, src_filename, 0);

		    if (audio_stream == null && video_stream == null) {
		    	System.out.format("Could not find audio or video stream in the input, aborting\n");
	        	System.exit(1);
		    }

		    frame = av_frame_alloc();
		    if (frame.isNull()) {
		    	System.out.format("Could not allocate frame\n");
	        	System.exit(1);
		    }

		    /* initialize packet, set data to null, let the demuxer fill it */
			AVPacket pkt = new AVPacket();
		    av_init_packet(pkt);
		    pkt.data(null);
		    pkt.size(0);

		    if (video_stream != null)
		    	System.out.format("Demuxing video from file '%s' into '%s'\n", src_filename, video_dst_filename);
		    if (audio_stream != null)
		    	System.out.format("Demuxing audio from file '%s' into '%s'\n", src_filename, audio_dst_filename);

		    /* read frames from the file */
		    while (av_read_frame(fmt_ctx, pkt) >= 0) {
		    	decode_packet(pkt, false);
		        av_packet_unref(pkt);
		    }

		    /* flush cached frames */
		    decode_packet(null, true);

		    System.out.format("Demuxing succeeded.\n");

		    if (video_stream != null) {
		    	System.out.format("Play the output video file with the command:\n"
		               +"ffplay -f rawvideo -pix_fmt %s -video_size %dx%d %s\n",
		               av_get_pix_fmt_name(pix_fmt), width, height,
		               video_dst_filename);
		    }

		    if (audio_stream != null) {
		        int sfmt = audio_dec_ctx.sample_fmt();
		        int n_channels = audio_dec_ctx.channels();
		        String fmt;

		        if (av_sample_fmt_is_planar(sfmt) != 0) {
		            BytePointer packed = av_get_sample_fmt_name(sfmt);
		            System.out.format("Warning: the sample format the decoder produced is planar "
		                   +"(%s). This example will output the first channel only.\n",
		                   packed != null ? packed.getString() : "?");
		            sfmt = av_get_packed_sample_fmt(sfmt);
		            n_channels = 1;
		        }

		        if ((fmt = get_format_from_sample_fmt(sfmt)) == null)
		        	System.exit(1);

		        System.out.format("Play the output audio file with the command:\n"
		               +"ffplay -f %s -ac %d -ar %d %s\n",
		               fmt, n_channels, audio_dec_ctx.sample_rate(),
		               audio_dst_filename);
		    }
	    } finally {
		    avcodec_free_context(video_dec_ctx);
		    avcodec_free_context(audio_dec_ctx);
		    avformat_close_input(fmt_ctx);
		    if (video_dst_file != null)
		        video_dst_file.close();
		    if (audio_dst_file != null)
		        audio_dst_file.close();
		    av_frame_free(frame);
		    av_free(video_dst_data.get(0));
	    }
	}
	
}
