package ffmpeg.examples;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.javacpp.avcodec.av_packet_unref;
import static org.bytedeco.javacpp.avcodec.avcodec_alloc_context3;
import static org.bytedeco.javacpp.avcodec.avcodec_find_encoder;
import static org.bytedeco.javacpp.avcodec.avcodec_open2;
import static org.bytedeco.javacpp.avcodec.avcodec_parameters_copy;
import static org.bytedeco.javacpp.avcodec.avcodec_receive_packet;
import static org.bytedeco.javacpp.avcodec.avcodec_send_frame;
import static org.bytedeco.javacpp.avformat.AVFMT_NOFILE;
import static org.bytedeco.javacpp.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.javacpp.avformat.av_dump_format;
import static org.bytedeco.javacpp.avformat.av_interleaved_write_frame;
import static org.bytedeco.javacpp.avformat.av_read_frame;
import static org.bytedeco.javacpp.avformat.av_write_trailer;
import static org.bytedeco.javacpp.avformat.avformat_alloc_output_context2;
import static org.bytedeco.javacpp.avformat.avformat_close_input;
import static org.bytedeco.javacpp.avformat.avformat_find_stream_info;
import static org.bytedeco.javacpp.avformat.avformat_free_context;
import static org.bytedeco.javacpp.avformat.avformat_new_stream;
import static org.bytedeco.javacpp.avformat.avformat_open_input;
import static org.bytedeco.javacpp.avformat.avformat_write_header;
import static org.bytedeco.javacpp.avformat.avio_closep;
import static org.bytedeco.javacpp.avformat.avio_open;
import static org.bytedeco.javacpp.avutil.AVERROR_EOF;
import static org.bytedeco.javacpp.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.javacpp.avutil.AVMEDIA_TYPE_SUBTITLE;
import static org.bytedeco.javacpp.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.javacpp.avutil.AV_ROUND_NEAR_INF;
import static org.bytedeco.javacpp.avutil.AV_ROUND_PASS_MINMAX;
import static org.bytedeco.javacpp.avutil.av_dict_free;
import static org.bytedeco.javacpp.avutil.av_opt_set;
import static org.bytedeco.javacpp.avutil.av_rescale_q;
import static org.bytedeco.javacpp.avutil.av_rescale_q_rnd;
import static org.bytedeco.javacpp.presets.avutil.AVERROR_EAGAIN;

import java.util.logging.Logger;

import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avcodec.AVCodec;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVCodecParameters;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avformat.AVInputFormat;
import org.bytedeco.javacpp.avformat.AVOutputFormat;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.bytedeco.javacpp.avutil.AVFrame;

/**
 * Hello world!
 *
 */
public class Remuxing {

	private static final Logger LOGGER = Logger.getLogger(Remuxing.class.getName());

	static void encode(AVCodecContext enc_ctx, AVFrame frame, AVPacket pkt, AVFormatContext ctx) throws Exception {
		int ret;

		/* send the frame to the encoder */
		if (frame != null)
			System.out.println("Send frame " + frame.pts() + "\n");

		ret = avcodec_send_frame(enc_ctx, frame);

		if (ret < 0) {
			System.out.println("Error sending a frame for encoding\n");
			System.exit(1);
		}

		while (ret >= 0) {
			ret = avcodec_receive_packet(enc_ctx, pkt);

			if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
				return;

			if (ret < 0) {
				System.out.println("Error during encoding\n");
				System.exit(1);
			}

			System.out.println("Write packet " + pkt.pts() + " (size=" + pkt.size() + ")\n");
			av_interleaved_write_frame(ctx, pkt);

			av_packet_unref(pkt);
		}
	}

	@SuppressWarnings({ "rawtypes", "resource" })
	public static void main(String[] args) throws Exception {
		String in_filename  = "Until_You(Shayne_Ward)_zing.mp3";
		String out_filename = "UntilYou-ShayneWard.mp4";

		AVFormatContext ifmt_ctx = new AVFormatContext(null);
		AVFormatContext ofmt_ctx = new AVFormatContext(null);

		AVCodec codec;
		AVCodecContext c = null;

		/* find the mpeg1video encoder */
		codec = avcodec_find_encoder(AV_CODEC_ID_H264);
		if (codec == null) {
			System.out.println("Codec not found\n");
			System.exit(1);
		}

		c = avcodec_alloc_context3(codec);
		if (c == null) {
			System.out.println("Could not allocate video codec context\n");
			System.exit(1);
		}

		/* put sample parameters */
		c.bit_rate(400000);

		/* resolution must be a multiple of two */
		c.width(352);
		c.height(288);

		/* frames per second */
		c.time_base().num(1);
		c.time_base().den(25);

		/*
		 * emit one intra frame every ten frames check frame pict_type before passing
		 * frame to encoder, if frame.pict_type is AV_PICTURE_TYPE_I then gop_size is
		 * ignored and the output of encoder will always be I frame irrespective to
		 * gop_size
		 */
		c.gop_size(10);
		c.max_b_frames(1);
		c.pix_fmt(AV_PIX_FMT_YUV420P);

		if (codec.id() == AV_CODEC_ID_H264)
			av_opt_set(c.priv_data(), "preset", "slow", 0);

		/* open it */
		AVDictionary avDictionary = new AVDictionary(null);
		if (avcodec_open2(c, codec, avDictionary) < 0) {
			System.out.println("Could not open codec");
			System.exit(1);
		}
		av_dict_free(avDictionary);

		AVInputFormat avInputFormat = new AVInputFormat(null);
		avDictionary = new AVDictionary(null);
		if (avformat_open_input(ifmt_ctx, in_filename, avInputFormat, avDictionary) < 0)
			throw new Exception("Could not open input file "+ in_filename);
		av_dict_free(avDictionary);

		// Read packets of a media file to get stream information
		if (avformat_find_stream_info(ifmt_ctx, (PointerPointer) null) < 0)
			throw new Exception("avformat_find_stream_info() error:\tFailed to retrieve input stream information");

		// Print input info
		av_dump_format(ifmt_ctx, 0, in_filename, 0);

		// Alloc output context
		if (avformat_alloc_output_context2(ofmt_ctx, null, null, out_filename) < 0)
			throw new Exception("avformat_alloc_output_context2() error:\tCould not create output context\n");

		int stream_index = 0;

		int[] stream_mapping = new int[ifmt_ctx.nb_streams()];

		AVOutputFormat ofmt = ofmt_ctx.oformat();
		
		for (int stream_idx = 0; stream_idx < stream_mapping.length; stream_idx++) {
			AVStream in_stream = ifmt_ctx.streams(stream_idx);

			AVCodecParameters in_codedpar = in_stream.codecpar();

			if (in_codedpar.codec_type() != AVMEDIA_TYPE_AUDIO && in_codedpar.codec_type() != AVMEDIA_TYPE_VIDEO
					&& in_codedpar.codec_type() != AVMEDIA_TYPE_SUBTITLE) {
				stream_mapping[stream_idx] = -1;
				continue;
			}

			stream_mapping[stream_idx] = stream_index++;

			AVStream out_stream = avformat_new_stream(ofmt_ctx, null);

			if (avcodec_parameters_copy(out_stream.codecpar(), in_codedpar) < 0)
				LOGGER.severe("Failed to copy codec parameters");

			out_stream.codecpar().codec_tag(0);
		}
		
		// Print output format
		av_dump_format(ofmt_ctx, 0, out_filename, 1);

		// open file
		if ((ofmt.flags() & AVFMT_NOFILE) == 0) {
			AVIOContext pb = new AVIOContext(null);
			if (avio_open(pb, out_filename, AVIO_FLAG_WRITE) < 0)
				throw new Exception("avio_open() error:\tCould not open output file '%s'" + out_filename);
			ofmt_ctx.pb(pb);
		}

		// Write header
		AVDictionary avOutDict = new AVDictionary(null);
		if (avformat_write_header(ofmt_ctx, avOutDict) < 0)
			LOGGER.severe("Error occurred when opening output file");

		AVPacket pkt = new AVPacket();

		for (;;) {
			AVStream in_stream, out_stream;

			// Return the next frame of a stream.
			if (av_read_frame(ifmt_ctx, pkt) < 0)
				break;

			in_stream = ifmt_ctx.streams(pkt.stream_index());
			if (pkt.stream_index() >= stream_mapping.length || stream_mapping[pkt.stream_index()] < 0) {
				av_packet_unref(pkt);
				continue;
			}

			pkt.stream_index(stream_mapping[pkt.stream_index()]);

			out_stream = ofmt_ctx.streams(pkt.stream_index());

			// log_packet
			pkt.pts(av_rescale_q_rnd(pkt.pts(), in_stream.time_base(), out_stream.time_base(),
					AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
			pkt.dts(av_rescale_q_rnd(pkt.dts(), in_stream.time_base(), out_stream.time_base(),
					AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
			pkt.duration(av_rescale_q(pkt.duration(), in_stream.time_base(), out_stream.time_base()));
			pkt.pos(-1);

			synchronized (ofmt_ctx) {
				if (av_interleaved_write_frame(ofmt_ctx, pkt) < 0)
					throw new Exception("av_write_frame() error:\tWhile muxing packet\n");
			}

			av_packet_unref(pkt);
		}

		av_write_trailer(ofmt_ctx);

		avformat_close_input(ifmt_ctx);

		if (!ofmt_ctx.isNull() && (ofmt.flags() & AVFMT_NOFILE) == 0)
			avio_closep(ofmt_ctx.pb());

		avformat_free_context(ofmt_ctx);
	}

}
