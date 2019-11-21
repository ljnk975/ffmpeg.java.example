package topica.linhnv5.test.ffmpeg;

import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.swscale.*;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avcodec.AVCodec;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avformat.AVInputFormat;
import org.bytedeco.javacpp.avformat.AVOutputFormat;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.bytedeco.javacpp.avutil.AVFrame;
import org.bytedeco.javacpp.swscale.SwsContext;

/**
 * Encoding video, using ffmpeg lib<br/>
 * Input image frame and audio file
 * @author ljnk975
 */
public class VideoEncoding {

	/**
	 * The output file name
	 */
	private String filename;

	/**
	 * output width
	 */
	private int width;
	
	/**
	 * output height
	 */
	private int height;

	/**
	 * Create video encoding with no sound stream
	 * @param filename
	 * @param width
	 * @param height
	 * @throws VideoEncodingException
	 */
	public VideoEncoding(String filename, int width, int height) throws VideoEncodingException {
		this.filename = filename;
		this.width = width;
		this.height = height;

		this.init(null);
	}

	/**
	 * Create video encoding with sound stream
	 * @param filename
	 * @param width
	 * @param height
	 * @param inMusic
	 * @throws VideoEncodingException
	 */
	public VideoEncoding(String filename, int width, int height, AVStream inMusic) throws VideoEncodingException {
		this.filename = filename;
		this.width = width;
		this.height = height;

		this.init(inMusic);
	}

	/**
	 * @return the filename
	 */
	public String getFilename() {
		return filename;
	}

	/**
	 * @return the width
	 */
	public int getWidth() {
		return width;
	}

	/**
	 * @return the height
	 */
	public int getHeight() {
		return height;
	}

	/**
	 * @return the fps
	 */
	public int getFrameRate() {
		return STREAM_NB_FRAMES;
	}

	private AVOutputFormat fmt;
    private AVFormatContext oc;

    private AVStream videoStream;
    private AVStream audioStream;

    private AVCodecContext videoCctx;

	private final int STREAM_NB_FRAMES = 30;
	private final int STREAM_PIX_FMT = AV_PIX_FMT_YUV420P;

	/**
	 * Init ffmpeg libary
	 * @param inStream audio stream input
	 * @throws VideoEncodingException
	 */
	private void init(AVStream inStream) throws VideoEncodingException {
		int ret;

		/* Format Context */
		oc = new AVFormatContext();

		/* allocate the output media context */
	    ret = avformat_alloc_output_context2(oc, null, null, filename);
	    if (ret < 0)
	        throw new VideoEncodingException("Could not deduce output format from file extension: "+av_err2str(ret));
	    fmt = oc.oformat();

	    /* Add the audio and video streams using the default format codecs
	     * and initialize the codecs. */
	    videoStream = null;
	    audioStream = null;

	    if (fmt.video_codec() != AV_CODEC_ID_NONE)
	        videoStream = addVideoStream(fmt.video_codec());
	    if (inStream != null)
	        audioStream = addAudioStream(inStream);

	    /* Now that all the parameters are set, we can open the audio and
	     * video codecs and allocate the necessary encode buffers. */
	    if (videoStream != null)
	        openVideo();
	    if (audioStream != null)
	        openAudio();

	    av_dump_format(oc, 0, filename, 1);
	    System.err.flush();

	    /* open the output file, if needed */
	    if ((fmt.flags() & AVFMT_NOFILE) == 0) {
	    	AVIOContext pb = new AVIOContext(null);
	        ret = avio_open(pb, filename, AVIO_FLAG_WRITE);
	        if (ret < 0)
	        	throw new VideoEncodingException("Could not open file: "+av_err2str(ret));
	        oc.pb(pb);
	    }

	    /* Write the stream header, if any. */
	    ret = avformat_write_header(oc, new AVDictionary(null));
	    if (ret < 0)
	    	throw new VideoEncodingException("Error occurred when opening output file: "+av_err2str(ret));

	    if (frame != null)
	        frame.pts(0);
	}

	/**
	 * Close video encoding
	 * @throws VideoEncodingException
	 */
	public void close() throws VideoEncodingException {
	    /* Write the trailer, if any. The trailer must be written before you
	     * close the CodecContexts open when you wrote the header; otherwise
	     * av_write_trailer() may try to use memory that was freed on
	     * av_codec_close(). */
	    av_write_trailer(oc);

	    /* Close each codec. */
	    if (videoStream != null)
	        closeVideo();

	    if (audioStream != null)
	        closeAudio();

	    if ((fmt.flags() & AVFMT_NOFILE) > 0)
	        /* Close the output file. */
	        avio_close(oc.pb());

	    /* free the stream */
	    avformat_free_context(oc);
	}

	/**
	 * Add new video stream.
	 * @param codecId video codec id
	 * @return the video stream
	 * @throws VideoEncodingException
	 */
	private AVStream addVideoStream(int codecId) throws VideoEncodingException {
		int ret;

		AVStream st;

		/* find the encoder */
		AVCodec codec = avcodec_find_encoder(codecId);
		if (codec.isNull())
			throw new VideoEncodingException("Could not find encoder for " + avcodec_get_name(codecId));

		AVCodecContext c;
	    if ((c = avcodec_alloc_context3(codec)).isNull())
	    	throw new VideoEncodingException("Failed to allocate codec context");
	    videoCctx = c;

		System.out.println("Add video stream, codec="+codec.name().getString());

		st = avformat_new_stream(oc, codec);
		if (st == null)
			throw new VideoEncodingException("Could not allocate stream");

		st.id(oc.nb_streams() - 1);
		c.codec_id(codec.id());
		c.bit_rate(400000);

		/* Resolution must be a multiple of two. */
		c.width(width);
		c.height(height);

		/*
		 * timebase: This is the fundamental unit of time (in seconds) in terms of which
		 * frame timestamps are represented. For fixed-fps content, timebase should be
		 * 1/framerate and timestamp increments should be identical to 1.
		 */
		c.time_base().den(STREAM_NB_FRAMES);
		c.time_base().num(1);
		c.gop_size(12); /* emit one intra frame every twelve frames at most */
		c.pix_fmt(STREAM_PIX_FMT);

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

		/* Some formats want stream headers to be separate. */
		if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) > 0)
			c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);

	    /* open it */
	    ret = avcodec_open2(c, codec, new AVDictionary(null));
	    if (ret < 0)
	    	throw new VideoEncodingException("Could not open codec: "+av_err2str(ret));

	    /* Codec */
	    st.codec(c);

	    return st;
	}

	/**
	 * Add new audio stream
	 * @param inStream input audio stream
	 * @return the audio stream
	 * @throws VideoEncodingException
	 */
	private AVStream addAudioStream(AVStream inStream) throws VideoEncodingException {
		AVStream st = avformat_new_stream(oc, null);

		if (avcodec_parameters_copy(st.codecpar(), inStream.codecpar()) < 0)
			throw new VideoEncodingException("Failed to copy codec parameters");

		System.out.println("Add audio stream, codec="+avcodec_get_name(st.codecpar().codec_id()).getString());

		st.codecpar().codec_tag(0);

		return st;
	}

	/**
	 * Write stream packet from input to output
	 * @param pkt       the av packet
	 * @param inStream  input av stream
	 * @param outStream output av stream
	 * @throws VideoEncodingException
	 */
    private void writeAVPacket(AVPacket pkt, AVStream inStream, AVStream outStream) throws VideoEncodingException {
		// Stream index
		pkt.stream_index(outStream.index());

		// log_packet
		pkt.pts(av_rescale_q_rnd(pkt.pts(), inStream.time_base(), outStream.time_base(),
				AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
		pkt.dts(av_rescale_q_rnd(pkt.dts(), inStream.time_base(), outStream.time_base(),
				AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
		pkt.duration(av_rescale_q(pkt.duration(), inStream.time_base(), outStream.time_base()));
		pkt.pos(-1);

		if (av_interleaved_write_frame(oc, pkt) < 0)
			throw new VideoEncodingException("av_write_frame() error:\tWhile muxing packet\n");

		av_packet_unref(pkt);
    }

    /**************************************************************/
	/* audio output */
	private static String av_err2str(int errnum) {
		BytePointer data = new BytePointer(new byte[AV_ERROR_MAX_STRING_SIZE]);
		av_make_error_string(data, AV_ERROR_MAX_STRING_SIZE, errnum);
		return data.getString();
	}

	/**
	 * Open audio stream
	 * @throws VideoEncodingException
	 */
	private void openAudio() throws VideoEncodingException {
	}

	/**
	 * Write audio packet
	 * @param pkt      av packet
	 * @param inStream input audio stream
	 * @throws VideoEncodingException
	 */
    public void writeAudioPacket(AVPacket pkt, AVStream inStream) throws VideoEncodingException {
		writeAVPacket(pkt, inStream, audioStream);
    }

    /**
     * Get audio stream time
     * @return audio stream time
     */
    public double getAudioTime() {
        return av_stream_get_end_pts(audioStream) * av_q2d(audioStream.time_base());
    }

    /**
     * Close audio stream
     */
    private void closeAudio() {
	}

	/**************************************************************/
	/* video output */
    /**
     * Video frame
     */
	private AVFrame frame;
	
	/**
	 * Video frame counter
	 */
	private int frameCount;

	private SwsContext swsCtx = null;

	/**
	 * Open video stream
	 * @throws VideoEncodingException
	 */
    private void openVideo() throws VideoEncodingException {
	    AVCodecContext c = videoCctx;

	    /* Allocate the encoded raw picture. */
	    frame = av_frame_alloc();
	    if (frame.isNull())
	    	throw new VideoEncodingException("Could not allocate picture");

	    frame.width(c.width());
	    frame.height(c.height());
	    frame.format(c.pix_fmt());

        if (av_frame_get_buffer(frame, 32) < 0)
        	throw new VideoEncodingException("Failed to allocate picture");
	}

    /**
     * Send video frame to encode
     * @param frame the video frame
     * @throws VideoEncodingException
     */
    private void videoSendFrame(AVFrame frame) throws VideoEncodingException {
	    int ret;

	    AVStream st = videoStream;
	    AVCodecContext c = videoCctx;

	    /* encode the image */
	    ret = avcodec_send_frame(c, frame);
        if (ret < 0)
        	throw new VideoEncodingException("Failed to send video frame: "+av_err2str(ret));

        AVPacket pkt = new AVPacket();
        av_init_packet(pkt);
        pkt.data(null);
        pkt.size(0);

        while (avcodec_receive_packet(c, pkt) == 0) {
    	    pkt.stream_index(st.index());

            /* Write the compressed frame to the media file. */
    	    ret = av_interleaved_write_frame(oc, pkt);
    	    if (ret != 0)
    	    	throw new VideoEncodingException("Error while writing audio frame: "+av_err2str(ret));

    	    av_packet_unref(pkt);
        }
    }

    /**
     * Write next video frame in argb format
     * @param data rgb data
     * @throws VideoEncodingException
     */
	public void writeVideoFrame(PointerPointer<BytePointer> data) throws VideoEncodingException {
	    AVStream st = videoStream;
	    AVCodecContext c = videoCctx;

	    if (swsCtx == null)
        	swsCtx = sws_getContext(c.width(), c.height(), AV_PIX_FMT_0RGB32, c.width(), c.height(), AV_PIX_FMT_YUV420P, SWS_BICUBIC, null, null, (DoublePointer) null);

        IntPointer inLinesize = new IntPointer(new int[] {4 * c.width()});

        // From RGB to YUV
        sws_scale(swsCtx, data, inLinesize, 0, c.height(), frame.data(), frame.linesize());

        // Add frames
	    frameCount = getFrameCount() + 1;
	    videoSendFrame(frame);
        frame.pts(frame.pts() + av_rescale_q(1, c.time_base(), st.time_base()));
	}

    /**
     * Write next video frame in argb format
     * @param rgb rgb data
     * @throws VideoEncodingException
     */
	public void writeVideoFrame(int[] rgb) throws VideoEncodingException {
		writeVideoFrame(new PointerPointer<BytePointer>(rgb));
	}

    /**
     * Write next video frame in argb format
     * @param rgb rgb data
     * @throws VideoEncodingException
     */
	public void writeVideoFrame(byte[] rgb) throws VideoEncodingException {
		writeVideoFrame(new PointerPointer<BytePointer>(rgb));
	}

	/**
	 * Write next video frame
	 * @param image the next video frame image
	 * @throws VideoEncodingException
	 */
	public void writeVideoFrame(BufferedImage image) throws VideoEncodingException {
		writeVideoFrame(((DataBufferByte) image.getRaster().getDataBuffer()).getData());
	}

	/**
	 * Get video stream time
	 * @return video stream time
	 */
	public double getVideoTime() {
        return av_stream_get_end_pts(videoStream) * av_q2d(videoStream.time_base());
	}

	/**
	 * Close video stream
	 * @throws VideoEncodingException
	 */
	private void closeVideo() throws VideoEncodingException {
		// flush video
		videoSendFrame(null);

		// free
		av_frame_free(frame);
	    avcodec_free_context(videoCctx);
	    sws_freeContext(swsCtx);
	}

	/**
	 * Get current frame count
	 * @return the frame count
	 */
	public int getFrameCount() {
		return frameCount;
	}

	public static void main(String[] args) throws Exception {
		String in_backname  = "back02.jpg";
		String in_filename  = "Until_You(Shayne_Ward)_zing.mp3";
		String out_filename = "Test.mp4";

		int ret;

		BufferedImage img = ImageIO.read(new File(in_backname));

		int[] rgb = new int[img.getWidth()*img.getHeight()];
		img.getRGB(0, 0, img.getWidth(), img.getHeight(), rgb, 0, img.getWidth());

		int w = img.getWidth()/2*2;
		int h = img.getHeight()/2*2;

		AVFormatContext ifmt_ctx = new AVFormatContext(null);

		AVInputFormat avInputFormat = new AVInputFormat(null);
		ret = avformat_open_input(ifmt_ctx, in_filename, avInputFormat, new AVDictionary(null));
		if (ret < 0)
			throw new Exception("Could not open input file "+ in_filename+": "+av_err2str(ret));

		// Read packets of a media file to get stream information
		ret = avformat_find_stream_info(ifmt_ctx, new AVDictionary(null));
		if (ret < 0)
			throw new Exception("avformat_find_stream_info() error: "+av_err2str(ret));

		// Print input info
		av_dump_format(ifmt_ctx, 0, in_filename, 0);
		System.err.flush();

		// In stream
		AVStream in_stream = ifmt_ctx.streams(0);

		// Video out
		VideoEncoding maker = new VideoEncoding(out_filename, w, h, in_stream);

		AVPacket pkt = new AVPacket();
		for (;;) {
			double video_time = maker.getVideoTime();
			double audio_time = maker.getAudioTime();
	        if (audio_time < video_time) {
		        // Return the next frame of a stream.
				if (av_read_frame(ifmt_ctx, pkt) < 0)
					break;

				if (pkt.stream_index() != in_stream.index())
					continue;

				maker.writeAudioPacket(pkt, in_stream);
	        } else
	        	maker.writeVideoFrame(rgb);
			System.out.print("\rFrame: "+maker.getFrameCount()+" time: "+video_time);
		}
		
		avformat_close_input(ifmt_ctx);

		maker.close();
	}

}
